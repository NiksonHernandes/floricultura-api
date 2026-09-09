package com.floricultura.api.service;

import com.floricultura.api.repository.ProdutoImagemMetaProjection;
import com.floricultura.api.repository.ProdutoImagemProjection;
import com.floricultura.api.repository.ProdutoImagemVarianteProjection;
import com.floricultura.api.repository.ProdutoRepository;
import com.floricultura.api.web.dto.ProdutoResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Regra de servir/remover/enviar a imagem do produto no banco (M3 + M5.2). O binario vive <b>fora</b>
 * da {@code @Entity Produto} (AD-SQ-38): leitura pelas projecoes nativas {@link
 * ProdutoRepository#findImagemById}/{@link ProdutoRepository#findVarianteById}, gravacao/remocao pelos
 * {@code UPDATE}/{@code INSERT ... ON CONFLICT}/{@code DELETE} nativos.
 *
 * <p><b>Upload (T-M3-3 + M5.2/T-M5.2-3):</b> a validacao vai do barato ao caro (vazio → tamanho por
 * metadado → content-type declarado → <i>magic bytes</i>), <b>inalterada em ordem e natureza</b> — so o
 * teto muda (2 MB, §3.7). Apos validar, o {@link ImagemProcessor} <b>decodifica e gera 3 variantes</b>
 * (original ≤1280 / medio ≤800 / thumb ≤200, resize→encode WebP q80 c/ fallback JPEG); a gravacao e
 * <b>atomica</b> (mesma transacao): original em {@code produto.imagem} (dirige {@code temImagem}) +
 * thumb/medio em {@code produto_imagem_variante} (delete+upsert, sem variantes orfas — C-R9).
 *
 * <p><b>Servir (M5.2/T-M5.2-4):</b> {@link #buscarMetadados} le so content-type + {@code atualizado_em}
 * (SEM bytea) p/ 404 + ETag; {@link #buscarBinario} materializa o binario da variante pedida so no
 * cache-miss, com <b>fallback gracioso ao original</b> quando a variante nao existe (legado — C-R5).
 *
 * <p>{@link ProdutoRepository} injetado como {@link Lazy} (mesmo padrao de {@code ProdutoService}):
 * preserva os smokes do M0 que sobem sem JPA.
 */
@Service
public class ProdutoImagemService {

    /** Whitelist de content-type aceito (SPEC-M3 §3.3/AD-SQ-38) — casa o CHECK {@code ck_produto_imagem_tipo}. */
    private static final Set<String> TIPOS_ACEITOS =
            Set.of("image/jpeg", "image/png", "image/webp");

    /** Magic bytes por tipo (SPEC-M3 §3.3): JPEG {@code FF D8 FF}. */
    private static final byte[] MAGIC_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    /** PNG {@code 89 50 4E 47 0D 0A 1A 0A}. */
    private static final byte[] MAGIC_PNG =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    /** WEBP: {@code RIFF} nos bytes 0-3 e {@code WEBP} nos bytes 8-11 (12 bytes lidos). */
    private static final byte[] WEBP_RIFF = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP_WEBP = {0x57, 0x45, 0x42, 0x50};

    private static final int PREFIXO_MAGIC = 12;
    private static final int MAX_FILENAME = 255;

    private final ProdutoRepository produtoRepository;
    private final ImagemProcessor imagemProcessor;
    private final long maxBytes;

    public ProdutoImagemService(
            @Lazy ProdutoRepository produtoRepository,
            ImagemProcessor imagemProcessor,
            @Value("${app.upload.imagem.max-bytes:2097152}") long maxBytes) {
        this.produtoRepository = produtoRepository;
        this.imagemProcessor = imagemProcessor;
        this.maxBytes = maxBytes;
    }

    /**
     * Metadados <b>leves</b> (SEM bytea) para o {@code GET .../imagem}: content-type + epoch de {@code
     * atualizado_em} (ETag). <b>404</b> quando o produto nao existe (projecao vazia) <b>ou</b> existe sem
     * imagem ({@code imagemContentType == null}) → {@link ImagemNaoEncontradaException} (CA-8), nunca 401.
     */
    @Transactional(readOnly = true)
    public ImagemMetadados buscarMetadados(Long id) {
        ProdutoImagemMetaProjection meta = produtoRepository.findImagemMetaById(id)
                .orElseThrow(ImagemNaoEncontradaException::new);
        if (meta.getImagemContentType() == null) {
            throw new ImagemNaoEncontradaException();
        }
        long epoch = meta.getAtualizadoEmEpoch() != null ? meta.getAtualizadoEmEpoch() : 0L;
        return new ImagemMetadados(meta.getImagemContentType(), epoch);
    }

    /**
     * Materializa o binario da variante pedida (cache-miss). {@code original} → {@code produto.imagem};
     * {@code thumb}/{@code medio} → {@code produto_imagem_variante}, com <b>fallback gracioso ao
     * original</b> quando a variante nao existe (legado M3 / upload so-original — C-R5). Nunca 404 por
     * variante ausente quando ha imagem; sem imagem/produto inexistente → 404.
     */
    @Transactional(readOnly = true)
    public ImagemBinario buscarBinario(Long id, TamanhoImagem tamanho) {
        if (tamanho.isVariante()) {
            ProdutoImagemVarianteProjection v = produtoRepository
                    .findVarianteById(id, tamanho.wire()).orElse(null);
            if (v != null) {
                return new ImagemBinario(v.getImagem(), v.getImagemContentType());
            }
            // Fallback gracioso: legado sem variante → serve o original (C-R5).
        }
        return original(id);
    }

    private ImagemBinario original(Long id) {
        ProdutoImagemProjection p = produtoRepository.findImagemById(id)
                .orElseThrow(ImagemNaoEncontradaException::new);
        if (p.getImagemContentType() == null) {
            throw new ImagemNaoEncontradaException();
        }
        return new ImagemBinario(p.getImagem(), p.getImagemContentType());
    }

    /**
     * Envia/substitui a imagem do produto (CA-2/CA-4..C6 + M5.2 CA-C1/C2/C3/C8/C9). Valida em cascata
     * (barato→caro, inalterada), <b>decodifica + gera as 3 variantes</b> ({@link ImagemProcessor}) e grava
     * <b>atomicamente</b> o original em {@code produto.imagem} + thumb/medio em {@code
     * produto_imagem_variante} (delete+upsert), bumpando {@code atualizado_em}. Produto inexistente →
     * {@link ProdutoNaoEncontradoException} (404). Devolve o {@link ProdutoResponse} relido ({@code
     * temImagem:true}).
     *
     * @throws ImagemInvalidaException  falha de validacao OU decode que falha apos os magic bytes → 400
     * @throws ProdutoNaoEncontradoException produto inexistente → 404
     * @throws IOException              falha real de transporte/I-O ao ler o upload → 500 (nao e validacao)
     */
    @Transactional
    public ProdutoResponse enviar(Long id, MultipartFile arquivo) throws IOException {
        // 1) ausente/vazio — sem ler bytes.
        if (arquivo == null || arquivo.isEmpty()) {
            throw new ImagemInvalidaException("Envie um arquivo de imagem.");
        }
        // 2) tamanho — por metadado, sem materializar o arquivo (M5.2/§3.7: teto 2 MB).
        if (arquivo.getSize() > maxBytes) {
            throw new ImagemInvalidaException("Imagem excede o tamanho maximo de 2 MB.");
        }
        // 3) content-type declarado — whitelist (octet-stream/ausente cai aqui).
        String contentType = normalizarTipo(arquivo.getContentType());
        if (!TIPOS_ACEITOS.contains(contentType)) {
            throw new ImagemInvalidaException(
                    "Tipo de imagem nao suportado (use JPG, PNG ou WEBP).");
        }
        // 4) anti-spoofing — le so o prefixo e confere contra o tipo declarado.
        if (!magicBytesCasam(contentType, lerPrefixo(arquivo))) {
            throw new ImagemInvalidaException(
                    "O conteudo do arquivo nao corresponde a uma imagem JPG/PNG/WEBP valida.");
        }
        // 4.5) produto existe? Checa ANTES do decode caro — produto inexistente → 404 (nao 400) mesmo com
        // imagem que falharia o decode; e evita processar toa. existsById e leve (nao materializa bytea).
        if (!produtoRepository.existsById(id)) {
            throw new ProdutoNaoEncontradoException();
        }
        // 5) decode + 3 variantes (resize→encode WebP q80 c/ fallback JPEG — M5.2/§3.5).
        ImagemProcessor.Resultado variantes;
        try {
            variantes = imagemProcessor.processar(arquivo.getBytes());
        } catch (ImagemProcessor.DecodeInvalidoException e) {
            // Passou os magic bytes mas o decoder rejeitou (truncado/corrompido) → 400 (C-R6/CA-C8).
            throw new ImagemInvalidaException(
                    "Nao foi possivel processar a imagem enviada (arquivo corrompido ou invalido).");
        }
        // 6) grava atomicamente: original na coluna canonica (dirige temImagem) + thumb/medio na tabela.
        ImagemProcessor.Variante original = variantes.original();
        int linhas = produtoRepository.atualizarImagem(
                id, original.bytes(), original.contentType(), sanitizarNome(arquivo.getOriginalFilename()));
        if (linhas == 0) {
            throw new ProdutoNaoEncontradoException();
        }
        produtoRepository.deleteVariantesById(id); // replace-set (C-R9): sem variantes orfas de upload anterior.
        gravarVariante(id, TamanhoImagem.MEDIO, variantes.medio());
        gravarVariante(id, TamanhoImagem.THUMB, variantes.thumb());

        return produtoRepository.findById(id)
                .map(ProdutoResponse::de)
                .orElseThrow(ProdutoNaoEncontradoException::new);
    }

    private void gravarVariante(Long id, TamanhoImagem tamanho, ImagemProcessor.Variante v) {
        produtoRepository.upsertVariante(
                id, tamanho.wire(), v.bytes(), v.contentType(), v.largura(), v.altura(), v.tamanhoBytes());
    }

    /**
     * Remove a imagem do banco (zera bytea + metadados do original) <b>e apaga as variantes</b> na mesma
     * transacao (M5.2/C-R12), bumpando {@code atualizado_em}. <b>Idempotente:</b> produto existente sem
     * imagem ainda devolve 204 (o UPDATE afeta 1 linha; o DELETE de variantes afeta 0). Produto
     * inexistente → {@code 0} linhas → {@link ProdutoNaoEncontradoException} (404).
     */
    @Transactional
    public void remover(Long id) {
        if (produtoRepository.removerImagem(id) == 0) {
            throw new ProdutoNaoEncontradoException();
        }
        produtoRepository.deleteVariantesById(id);
    }

    // ----- validacao de upload (helpers privados) --------------------------------------------

    /** Normaliza o content-type declarado: {@code null}-safe, sem parametros, minusculo. */
    private static String normalizarTipo(String contentType) {
        if (contentType == null) {
            return "";
        }
        int ponto = contentType.indexOf(';'); // descarta parametros (ex.: "; charset=...")
        String tipo = ponto >= 0 ? contentType.substring(0, ponto) : contentType;
        return tipo.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Le apenas os {@value #PREFIXO_MAGIC} primeiros bytes do upload (sem materializar o todo). */
    private static byte[] lerPrefixo(MultipartFile arquivo) throws IOException {
        try (InputStream in = arquivo.getInputStream()) {
            return in.readNBytes(PREFIXO_MAGIC);
        }
    }

    /** O conteudo real casa o tipo declarado? (anti-spoofing por magic bytes — SPEC-M3 §3.3). */
    private static boolean magicBytesCasam(String contentType, byte[] prefixo) {
        return switch (contentType) {
            case "image/jpeg" -> comecaCom(prefixo, MAGIC_JPEG, 0);
            case "image/png" -> comecaCom(prefixo, MAGIC_PNG, 0);
            case "image/webp" -> comecaCom(prefixo, WEBP_RIFF, 0) && comecaCom(prefixo, WEBP_WEBP, 8);
            default -> false;
        };
    }

    /** {@code prefixo} contem {@code assinatura} a partir de {@code offset}? (prefixo curto → false). */
    private static boolean comecaCom(byte[] prefixo, byte[] assinatura, int offset) {
        if (prefixo.length < offset + assinatura.length) {
            return false;
        }
        for (int i = 0; i < assinatura.length; i++) {
            if (prefixo[offset + i] != assinatura[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Nome informativo, seguro: descarta qualquer caminho (mantem o basename), remove caracteres de
     * controle e trunca em {@value #MAX_FILENAME}. Retorna {@code null} se vazio/ausente (nao
     * influencia o storage — a imagem e {@code bytea} no banco, nao disco).
     */
    private static String sanitizarNome(String original) {
        if (original == null || original.isBlank()) {
            return null;
        }
        String base = original.substring(
                Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1);
        base = base.replaceAll("[\\p{Cntrl}]", "").trim();
        if (base.isEmpty()) {
            return null;
        }
        return base.length() > MAX_FILENAME ? base.substring(0, MAX_FILENAME) : base;
    }

    /** Metadados leves p/ 404 + ETag (SEM bytea): content-type do original + {@code atualizado_em} epoch-ms. */
    public record ImagemMetadados(String contentType, long atualizadoEmEpoch) {
    }

    /** Binario materializado de uma variante/original ao servir (cache-miss). */
    public record ImagemBinario(byte[] bytes, String contentType) {
    }
}
