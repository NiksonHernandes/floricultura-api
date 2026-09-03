package com.floricultura.api.service;

import com.floricultura.api.repository.ProdutoImagemProjection;
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
 * Regra de servir/remover/enviar a imagem do produto no banco (M3, CA-2/CA-4/CA-5/CA-6/CA-7/CA-8/
 * CA-10). O binario vive <b>fora</b> da {@code @Entity Produto} (AD-SQ-38): leitura pela projecao
 * nativa {@link ProdutoRepository#findImagemById}, gravacao/remocao pelos {@code UPDATE} nativos
 * {@link ProdutoRepository#atualizarImagem}/{@link ProdutoRepository#removerImagem} — todos da T-M3-1.
 *
 * <p><b>Upload (T-M3-3):</b> entrada binaria <b>nao confiavel</b> — a validacao vai do barato ao caro
 * (vazio → tamanho por metadado → content-type declarado → <i>magic bytes</i>) e so entao materializa
 * os bytes. Nunca confia em extensao nem no content-type declarado sem casar o conteudo real; nunca
 * loga o binario. O nome do arquivo e informativo (sanitizado) e nao influencia o storage (e
 * {@code bytea} no banco, nao disco).
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
    private final long maxBytes;

    public ProdutoImagemService(
            @Lazy ProdutoRepository produtoRepository,
            @Value("${app.upload.imagem.max-bytes:5242880}") long maxBytes) {
        this.produtoRepository = produtoRepository;
        this.maxBytes = maxBytes;
    }

    /**
     * Carrega o binario + content-type para servir o endpoint dedicado (CA-7). <b>404</b> quando o
     * produto nao existe (projecao vazia) <b>ou</b> existe sem imagem ({@code imagemContentType == null}
     * — pelo CHECK de coerencia da V5, isso implica {@code imagem} tambem nulo) → {@link
     * ImagemNaoEncontradaException} (CA-8), nunca 401. A projecao (native + interface) materializa os
     * bytes na propria query, entao e segura fora da transacao mesmo com {@code open-in-view=false}.
     */
    @Transactional(readOnly = true)
    public ProdutoImagemProjection buscarImagem(Long id) {
        ProdutoImagemProjection imagem = produtoRepository.findImagemById(id)
                .orElseThrow(ImagemNaoEncontradaException::new);
        if (imagem.getImagemContentType() == null) {
            throw new ImagemNaoEncontradaException();
        }
        return imagem;
    }

    /**
     * Envia/substitui a imagem do produto (CA-2/CA-4/CA-5/CA-6). Valida em cascata (barato→caro),
     * grava via {@code UPDATE} nativo com o content-type <b>confirmado</b> + filename sanitizado e bumpa
     * {@code atualizado_em}. Produto inexistente → {@link ProdutoNaoEncontradoException} (404). Devolve
     * o {@link ProdutoResponse} relido (com {@code temImagem:true}) — o SELECT pos-UPDATE, na mesma
     * transacao, ve a linha ja atualizada (a entity nao estava no cache L1).
     *
     * @throws ImagemInvalidaException  falha de validacao → 400 (CA-4/CA-5)
     * @throws ProdutoNaoEncontradoException produto inexistente → 404 (CA-6)
     * @throws IOException              falha real de transporte ao ler o upload → 500 (nao e validacao)
     */
    @Transactional
    public ProdutoResponse enviar(Long id, MultipartFile arquivo) throws IOException {
        // 1) ausente/vazio — sem ler bytes.
        if (arquivo == null || arquivo.isEmpty()) {
            throw new ImagemInvalidaException("Envie um arquivo de imagem.");
        }
        // 2) tamanho — por metadado, sem materializar o arquivo.
        if (arquivo.getSize() > maxBytes) {
            throw new ImagemInvalidaException("Imagem excede o tamanho maximo de 5 MB.");
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
        // 5) grava — bytes materializados so agora (arquivo ja garantido <= limite).
        int linhas = produtoRepository.atualizarImagem(
                id, arquivo.getBytes(), contentType, sanitizarNome(arquivo.getOriginalFilename()));
        if (linhas == 0) {
            throw new ProdutoNaoEncontradoException();
        }
        return produtoRepository.findById(id)
                .map(ProdutoResponse::de)
                .orElseThrow(ProdutoNaoEncontradoException::new);
    }

    /**
     * Remove a imagem do banco (zera bytea + metadados) e bumpa {@code atualizado_em} via o {@code
     * UPDATE} nativo (CA-10). <b>Idempotente:</b> o UPDATE afeta a linha do produto <b>existente</b>
     * tenha ou nao imagem → {@code 1} linha → 204. Produto inexistente → {@code 0} linhas →
     * {@link ProdutoNaoEncontradoException} (404).
     */
    @Transactional
    public void remover(Long id) {
        if (produtoRepository.removerImagem(id) == 0) {
            throw new ProdutoNaoEncontradoException();
        }
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
}
