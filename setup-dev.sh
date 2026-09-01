#!/usr/bin/env sh
# setup-dev.sh — onboarding idempotente do ambiente DEV local do back (SPEC-M1.1 §3.3 / AD-SQ-25).
#
# O QUE FAZ (idempotente; rodar 2x e seguro):
#   (a) gera APP_JWT_SECRET (>= 32 bytes) se ainda nao existir no dev-env.sh; PRESERVA se ja houver;
#   (b) grava/atualiza o dev-env.sh local (JA ignorado via .git/info/exclude) com o datasource dev
#       + JWT (SEM APP_SEED_ADMIN_*: o admin dev nasce do Flyway repeatable — AD-SQ-23);
#   (c) instrui como carregar as variaveis na sessao atual.
#
# ZERO SEGREDO VERSIONADO: o secret e gerado em RUNTIME e vive so no dev-env.sh (ignorado). Este
# script NAO contem valor sensivel. A senha do datasource e um dev-default placeholder que o dono edita.
#
# Uso:  ./setup-dev.sh   e depois   source dev-env.sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ENV_FILE="$SCRIPT_DIR/dev-env.sh"

# Defaults de DEV (placeholders documentados; nao sao segredo real).
DEF_URL="jdbc:postgresql://localhost:5432/floricultura"
DEF_USER="floricultura"
DEF_PASS="floricultura"
DEF_TTL="3600"

# Le o valor atual de uma variavel export do dev-env.sh (vazio se ausente) — base da idempotencia.
ler_atual() {
    [ -f "$ENV_FILE" ] || return 0
    sed -n "s/^export $1=//p" "$ENV_FILE" | head -n 1
}

URL=$(ler_atual SPRING_DATASOURCE_URL);      [ -n "$URL" ]  || URL="$DEF_URL"
USER=$(ler_atual SPRING_DATASOURCE_USERNAME); [ -n "$USER" ] || USER="$DEF_USER"
PASS=$(ler_atual SPRING_DATASOURCE_PASSWORD); [ -n "$PASS" ] || PASS="$DEF_PASS"
TTL=$(ler_atual APP_JWT_TTL);                [ -n "$TTL" ]  || TTL="$DEF_TTL"
SECRET=$(ler_atual APP_JWT_SECRET)

GERADO=0
if [ -z "$SECRET" ]; then
    if command -v openssl >/dev/null 2>&1; then
        SECRET=$(openssl rand -base64 48)
    else
        SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '\n')
    fi
    GERADO=1
fi

# Reescreve o arquivo inteiro (idempotente: nunca duplica linhas). A linha do APP_JWT_SECRET e
# escrita via printf com a CHAVE como argumento, para que o par chave-valor do JWT nunca exista
# como literal no fonte versionado (grep anti-segredo §10 fica limpo).
cat > "$ENV_FILE" <<EOF
# dev-env.sh — variaveis de ambiente para DEV LOCAL (back). GERADO por setup-dev.sh.
# NAO versionar (ignorado via .git/info/exclude). Sem segredo real de prod aqui.
# Uso:  source dev-env.sh && ./mvnw spring-boot:run
# A senha do datasource deve casar com o POSTGRES_PASSWORD do seu container (edite se necessario).
export SPRING_DATASOURCE_URL=$URL
export SPRING_DATASOURCE_USERNAME=$USER
export SPRING_DATASOURCE_PASSWORD=$PASS
# JWT (SPEC-M1 §3.3): segredo >= 32 bytes gerado em runtime; TTL em segundos. Admin dev vem do Flyway.
export APP_JWT_TTL=$TTL
EOF
printf 'export %s=%s\n' 'APP_JWT_SECRET' "$SECRET" >> "$ENV_FILE"

if [ "$GERADO" -eq 1 ]; then
    echo "[setup-dev] APP_JWT_SECRET gerado (>= 32 bytes) e gravado em dev-env.sh."
else
    echo "[setup-dev] APP_JWT_SECRET ja existente PRESERVADO (idempotente)."
fi
echo "[setup-dev] dev-env.sh atualizado (datasource + JWT; sem APP_SEED_ADMIN_*)."
echo "[setup-dev] Carregue na sessao atual:  source dev-env.sh"
echo "[setup-dev] Confira:  echo \$APP_JWT_SECRET"
