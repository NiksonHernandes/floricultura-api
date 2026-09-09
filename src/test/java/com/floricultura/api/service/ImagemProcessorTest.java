package com.floricultura.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Unitario do pipeline de imagem (M5.2/T-M5.2-3, CA-C1/C2/C3/C8 — SPEC-M5.2 §3.5). Roda o {@link
 * ImagemProcessor} in-memory (sem Spring) sobre fixtures determinISTICOS (gradiente suave, NAO ruido —
 * armadilha 6). Prova: 3 variantes com resize + no-upscale (CA-C1); encolhimento relevante (CA-C2);
 * os <b>dois ramos</b> do encoder — WebP quando disponivel / JPEG no fallback forcado por config
 * (CA-C3); decode que falha apos magic bytes → excecao de validacao (CA-C8).
 */
class ImagemProcessorTest {

    /** Gradiente suave determinISTICO (sem ruido) — comprime bem e e reprodutivel (armadilha 6). */
    private static BufferedImage gradiente(int largura, int altura) {
        BufferedImage img = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < altura; y++) {
            for (int x = 0; x < largura; x++) {
                int r = (x * 255) / Math.max(1, largura - 1);
                int b = (y * 255) / Math.max(1, altura - 1);
                int g = (r + b) / 2;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static int maxLado(byte[] bytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(img).as("variante deve ser decodavel").isNotNull();
        return Math.max(img.getWidth(), img.getHeight());
    }

    private static int[] dimensoes(byte[] bytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(img).as("variante deve ser decodavel").isNotNull();
        return new int[] {img.getWidth(), img.getHeight()};
    }

    // ----- CA-C1: 3 variantes, resize preservando proporcao, SEM upscale -----

    @Test
    void tresVariantes_resize_proporcaoPreservada() throws IOException {
        ImagemProcessor proc = new ImagemProcessor(true);
        ImagemProcessor.Resultado r = proc.processar(png(gradiente(1920, 1280)));

        // maior lado por variante: original 1280 / medio 800 / thumb 200.
        assertThat(maxLado(r.original().bytes())).isEqualTo(1280);
        assertThat(maxLado(r.medio().bytes())).isEqualTo(800);
        assertThat(maxLado(r.thumb().bytes())).isEqualTo(200);
        // proporcao 3:2 preservada (altura = 2/3 do maior lado).
        assertThat(dimensoes(r.original().bytes())).containsExactly(1280, 853);
        assertThat(dimensoes(r.medio().bytes())).containsExactly(800, 533);
        assertThat(dimensoes(r.thumb().bytes())).containsExactly(200, 133);
        // dimensoes gravadas na variante batem os bytes.
        assertThat(new int[] {r.thumb().largura(), r.thumb().altura()}).containsExactly(200, 133);
    }

    @Test
    void semUpscale_imagemMenorQueOsAlvos() throws IOException {
        ImagemProcessor proc = new ImagemProcessor(true);
        ImagemProcessor.Resultado r = proc.processar(png(gradiente(150, 100)));

        // 150x100 e menor que todos os alvos (1280/800/200) → NAO amplia (C-R2), so recomprime.
        assertThat(dimensoes(r.original().bytes())).containsExactly(150, 100);
        assertThat(dimensoes(r.medio().bytes())).containsExactly(150, 100);
        assertThat(dimensoes(r.thumb().bytes())).containsExactly(150, 100);
    }

    // ----- CA-C2: encolhimento relevante -----

    @Test
    void encolhimentoRelevante_originalEThumb() throws IOException {
        byte[] entrada = png(gradiente(1920, 1280));
        ImagemProcessor proc = new ImagemProcessor(true);
        ImagemProcessor.Resultado r = proc.processar(entrada);

        assertThat(r.original().bytes().length)
                .as("original recomprimido < 300 KB e menor que a entrada")
                .isLessThan(307200)
                .isLessThan(entrada.length);
        assertThat(r.thumb().bytes().length).as("thumb < 30 KB").isLessThan(30720);
    }

    // ----- CA-C3: dois ramos do encoder -----

    @Test
    void ramoFallback_forcadoPorConfig_gravaJpegSemExcecao() throws IOException {
        // webp-habilitado=false → efetivo false → JPEG q80, sem exceção (nunca quebra o upload).
        ImagemProcessor proc = new ImagemProcessor(false);
        assertThat(proc.isWebpDisponivel()).isFalse();
        ImagemProcessor.Resultado r = proc.processar(png(gradiente(400, 300)));

        assertThat(r.original().contentType()).isEqualTo("image/jpeg");
        assertThat(r.medio().contentType()).isEqualTo("image/jpeg");
        assertThat(r.thumb().contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void ramoWebp_quandoEncoderDisponivel_gravaWebp() throws IOException {
        ImagemProcessor proc = new ImagemProcessor(true);
        // So afere o ramo WebP quando o encoder nativo carrega neste host; senao o ramo fallback ja
        // esta coberto acima (o upload nunca falha por falta de WebP — CA-C3).
        Assumptions.assumeTrue(proc.isWebpDisponivel(),
                "encoder WebP indisponivel neste host — ramo fallback coberto separadamente");
        ImagemProcessor.Resultado r = proc.processar(png(gradiente(400, 300)));

        assertThat(r.original().contentType()).isEqualTo("image/webp");
        assertThat(r.thumb().contentType()).isEqualTo("image/webp");
    }

    // ----- CA-C8: decode que falha apos os magic bytes -----

    @Test
    void decodeFalho_lancaDecodeInvalido() {
        // Magic JPEG (FF D8 FF) + corpo lixo -> passa os magic bytes mas o decoder rejeita.
        byte[] corrompido = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5, 6, 7};
        ImagemProcessor proc = new ImagemProcessor(true);
        assertThatThrownBy(() -> proc.processar(corrompido))
                .isInstanceOf(ImagemProcessor.DecodeInvalidoException.class);
    }
}
