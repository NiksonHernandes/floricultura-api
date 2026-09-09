package com.floricultura.api.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pipeline de imagem do M5.2 (T-M5.2-3, SPEC-M5.2 §3.5): a partir dos bytes JA validados (tipo + magic
 * bytes, no {@link ProdutoImagemService}), <b>decodifica</b> e gera <b>3 variantes</b> — {@code
 * original} (≤1280px), {@code medio} (≤800px), {@code thumb} (≤200px) — cada uma <b>redimensionada</b>
 * (preserva proporcao, <b>sem upscale</b>) e depois <b>recomprimida</b> a q80: <b>WebP</b> quando o
 * encoder nativo esta disponivel, senao <b>JPEG</b> (fallback puro-Java). O upload <b>nunca</b> falha
 * por falta de WebP.
 *
 * <p><b>Decode do WebP de entrada (C-R7):</b> o reader TwelveMonkeys (puro-Java) e registrado no
 * ImageIO e cobre uploads {@code .webp} independentemente do writer nativo — decode sempre funciona; so
 * o encode WebP e que pode degradar para JPEG.
 *
 * <p><b>Deteccao do encoder WebP (runtime, 1x):</b> checa {@code getImageWritersByMIMEType("image/webp")}
 * <b>e</b> faz um encode-smoke de 1px (cobre "SPI presente mas nativo nao carrega"), cacheando o
 * resultado em {@link #webpDisponivel}. Efetivo = {@code webp-habilitado} (config, §3.7) &&
 * disponibilidade runtime — o dono pode forcar JPEG por config sem redeploy; o mesmo flag e o gancho
 * de teste do ramo fallback (CA-C3).
 *
 * <p><b>Alpha (C-R8):</b> cada variante e rasterizada em {@code TYPE_INT_RGB} sobre <b>branco</b> antes
 * do encode — evita o "pink-tint" ARGB→JPEG e mantem o WebP consistente (sem canal alpha).
 *
 * <p><b>AD-SQ-38:</b> opera so em memoria/bytes; nao toca @Entity nem materializa binario fora das
 * queries nativas dedicadas (a persistencia e responsabilidade do {@link ProdutoImagemService}).
 */
@Component
public class ImagemProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImagemProcessor.class);

    private static final String WEBP_MIME = "image/webp";
    private static final String WEBP_CONTENT_TYPE = "image/webp";
    private static final String JPEG_CONTENT_TYPE = "image/jpeg";
    private static final float QUALIDADE = 0.80f;

    private final boolean webpDisponivel;

    public ImagemProcessor(
            @Value("${app.upload.imagem.webp-habilitado:true}") boolean webpHabilitado) {
        boolean disponivel = webpHabilitado && detectarWebp();
        this.webpDisponivel = disponivel;
        if (webpHabilitado && !disponivel) {
            log.warn("WebP encoder indisponivel — gravando JPEG q80 (fallback).");
        } else if (!webpHabilitado) {
            log.info("WebP desabilitado por configuracao (app.upload.imagem.webp-habilitado=false) "
                    + "— gravando JPEG q80.");
        }
    }

    /** {@code true} se as variantes serao gravadas em WebP; {@code false} = fallback JPEG (§3.5). */
    public boolean isWebpDisponivel() {
        return webpDisponivel;
    }

    /**
     * Decodifica os bytes validados e gera as 3 variantes (original/medio/thumb). Decode que falha
     * (bytes que passaram os magic bytes mas estao truncados/corrompidos) → {@link DecodeInvalidoException}
     * (o servico traduz em 400, C-R6/CA-C8). {@link IOException} real de I/O propaga (500, nao e validacao).
     */
    public Resultado processar(byte[] bytes) throws IOException {
        BufferedImage src = decodificar(bytes);
        Variante original = gerar(src, TamanhoImagem.ORIGINAL);
        Variante medio = gerar(src, TamanhoImagem.MEDIO);
        Variante thumb = gerar(src, TamanhoImagem.THUMB);
        return new Resultado(original, medio, thumb);
    }

    private BufferedImage decodificar(byte[] bytes) {
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            // Bytes ja em memoria (sem transporte): IOException aqui = conteudo corrompido/truncado apos
            // os magic bytes (ex.: JPEG reader lanca IIOException) → validacao (400, C-R6), nao 500.
            throw new DecodeInvalidoException();
        }
        if (src == null) {
            // Sem reader que decodifique (formato nao suportado) ou stream vazio — validacao (400).
            throw new DecodeInvalidoException();
        }
        return src;
    }

    /**
     * Redimensiona (lado maximo do alvo, sem upscale) e recomprime a variante. {@code escala = alvo/maxLado}
     * quando {@code maxLado > alvo}; senao {@code 1.0} (mantem dimensoes, ainda recomprime — C-R2).
     */
    private Variante gerar(BufferedImage src, TamanhoImagem alvo) throws IOException {
        int maxLado = Math.max(src.getWidth(), src.getHeight());
        int limite = alvo.ladoMaximo();
        double escala = maxLado > limite ? (double) limite / maxLado : 1.0;
        int w = Math.max(1, (int) Math.round(src.getWidth() * escala));
        int h = Math.max(1, (int) Math.round(src.getHeight() * escala));

        BufferedImage rgb = rasterizarSobreBranco(src, w, h);
        if (webpDisponivel) {
            byte[] webp = encodeWebp(rgb);
            if (webp != null && webp.length > 0) {
                return new Variante(webp, WEBP_CONTENT_TYPE, w, h);
            }
            // Falha pontual do writer nativo apos o smoke — degrada esta variante para JPEG (nunca lanca).
            log.warn("Encode WebP falhou em runtime — gravando esta variante em JPEG q80.");
        }
        return new Variante(encodeJpeg(rgb), JPEG_CONTENT_TYPE, w, h);
    }

    /** Rasteriza {@code src} em {@code w}x{@code h} {@code TYPE_INT_RGB} sobre branco (flatten alpha, C-R8). */
    private static BufferedImage rasterizarSobreBranco(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /** Encode JPEG q80 puro-Java (Thumbnailator; {@code rgb} ja esta no tamanho final — {@code scale(1.0)}). */
    private static byte[] encodeJpeg(BufferedImage rgb) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Thumbnails.of(rgb)
                .scale(1.0)
                .outputFormat("jpg")
                .outputQuality(QUALIDADE)
                .toOutputStream(baos);
        return baos.toByteArray();
    }

    /** Encode WebP q80 via ImageIO SPI (webp-imageio). {@code null} se o writer nao produzir bytes. */
    private static byte[] encodeWebp(BufferedImage rgb) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType(WEBP_MIME);
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String[] tipos = param.getCompressionTypes();
                if (tipos != null && tipos.length > 0 && param.getCompressionType() == null) {
                    param.setCompressionType(tipos[0]);
                }
                param.setCompressionQuality(QUALIDADE);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(rgb, null, null), param);
            }
            return baos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /** Deteccao 1x do encoder WebP: SPI presente + encode-smoke de 1px (cobre nativo que nao carrega). */
    private static boolean detectarWebp() {
        try {
            if (!ImageIO.getImageWritersByMIMEType(WEBP_MIME).hasNext()) {
                return false;
            }
            byte[] smoke = encodeWebp(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));
            return smoke != null && smoke.length > 0;
        } catch (Throwable t) {
            // SPI presente mas nativo (.so/.dll) nao carrega — cai no fallback JPEG, sem quebrar o boot.
            return false;
        }
    }

    /** Uma variante recomprimida: bytes finais + content-type REAL + dimensoes efetivas. */
    public record Variante(byte[] bytes, String contentType, int largura, int altura) {
        public int tamanhoBytes() {
            return bytes.length;
        }
    }

    /** As 3 variantes de um upload (SPEC-M5.2 §3.5). */
    public record Resultado(Variante original, Variante medio, Variante thumb) {
    }

    /** Decode falhou apos os magic bytes (arquivo corrompido/truncado) — o servico traduz em 400. */
    public static class DecodeInvalidoException extends RuntimeException {
        public DecodeInvalidoException() {
            super("decode falhou");
        }
    }
}
