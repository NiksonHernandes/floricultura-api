package com.floricultura.api.domain;

/**
 * Fabrica de {@link Usuario} para a criacao por ADMIN (SPEC-M1 §3.2/§4, CA-7). Vive no MESMO pacote
 * de {@link Usuario} porque o construtor da entidade e {@code protected} (exigido pelo JPA, T-M1-1) —
 * este helper permite instancia-la a partir da camada de servico <b>sem alterar a entity</b> (§8/
 * handoff: "nao toque a entity").
 *
 * <p>Centraliza os invariantes de "novo usuario criado pelo ADMIN": nasce {@code ativo=true} e
 * {@code senha_provisoria=false} — a troca no 1o acesso deixou de ser forcada PARA TODOS (SPEC-M1.1
 * §3.1/AD-SQ-24); a coluna e mantida apenas informativa. O
 * {@code senha_hash} ja chega BCrypt-encoded do servico (§9 — a fabrica nunca ve a senha em texto).
 * {@code criado_em}/{@code atualizado_em} nao sao setados aqui: vem do {@code DEFAULT now()} do banco
 * (colunas {@code insertable=false}).
 */
public final class UsuarioFactory {

    private UsuarioFactory() {
        // Utilitaria — sem instancia.
    }

    /**
     * Cria um usuario ativo, sem forcar troca de senha (criacao por ADMIN — §3.2; SPEC-M1.1 §3.1).
     *
     * @param nome      nome de exibicao (ja validado pelo DTO)
     * @param email     e-mail unico (ja validado; unicidade checada no servico)
     * @param senhaHash hash BCrypt da senha (nunca a senha em texto — §9)
     * @param role      papel a gravar (o servico de criacao sempre passa {@code USER} — §3.2/AD-SQ-19)
     * @return entidade transiente pronta para {@code save}
     */
    public static Usuario novo(String nome, String email, String senhaHash, String role) {
        Usuario usuario = new Usuario();
        usuario.setNome(nome);
        usuario.setEmail(email);
        usuario.setSenhaHash(senhaHash);
        usuario.setRole(role);
        usuario.setAtivo(true);
        usuario.setSenhaProvisoria(false); // AD-SQ-24: 1o acesso nao forca troca (flag so informativa)
        return usuario;
    }
}
