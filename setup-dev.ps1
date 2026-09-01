# setup-dev.ps1 — onboarding idempotente do ambiente DEV local do back (SPEC-M1.1 §3.3 / AD-SQ-25).
#
# O QUE FAZ (idempotente; rodar 2x e seguro):
#   (a) gera APP_JWT_SECRET (>= 32 bytes) se ainda nao existir no dev-env.ps1; PRESERVA se ja houver;
#   (b) grava/atualiza o dev-env.ps1 local (JA ignorado via .git/info/exclude) com o datasource dev
#       + JWT (SEM APP_SEED_ADMIN_*: o admin dev nasce do Flyway repeatable — AD-SQ-23);
#   (c) instrui como carregar as variaveis na sessao atual.
#
# ZERO SEGREDO VERSIONADO: o secret e gerado em RUNTIME e vive so no dev-env.ps1 (ignorado). Este
# script NAO contem valor sensivel. A senha do datasource e um dev-default placeholder que o dono edita.
#
# Uso (note o ponto+espaco para carregar na sessao):  ./setup-dev.ps1   e depois   . .\dev-env.ps1
$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvFile   = Join-Path $ScriptDir 'dev-env.ps1'

# Defaults de DEV (placeholders documentados; nao sao segredo real).
$DefUrl  = 'jdbc:postgresql://localhost:5432/floricultura'
$DefUser = 'floricultura'
$DefPass = 'floricultura'
$DefTtl  = '3600'

# Le o valor atual de uma variavel $env: do dev-env.ps1 (vazio se ausente) — base da idempotencia.
function Get-Atual([string]$name) {
    if (-not (Test-Path $EnvFile)) { return '' }
    $pattern = '^\$env:' + $name + '\s*=\s*"(.*)"'
    $m = Select-String -Path $EnvFile -Pattern $pattern | Select-Object -First 1
    if ($m) { return $m.Matches[0].Groups[1].Value }
    return ''
}

$Url  = Get-Atual 'SPRING_DATASOURCE_URL';      if (-not $Url)  { $Url  = $DefUrl }
$User = Get-Atual 'SPRING_DATASOURCE_USERNAME'; if (-not $User) { $User = $DefUser }
$Pass = Get-Atual 'SPRING_DATASOURCE_PASSWORD'; if (-not $Pass) { $Pass = $DefPass }
$Ttl  = Get-Atual 'APP_JWT_TTL';                if (-not $Ttl)  { $Ttl  = $DefTtl }
$Secret = Get-Atual 'APP_JWT_SECRET'

$Gerado = $false
if (-not $Secret) {
    $rng   = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 48
    $rng.GetBytes($bytes)
    $Secret = [Convert]::ToBase64String($bytes)   # 48 bytes -> >= 32 bytes de entropia
    $Gerado = $true
}

# Reescreve o arquivo inteiro (idempotente: nunca duplica linhas). Nas linhas $env: o "=" leva
# espacos ao redor, logo o par chave-valor do JWT nunca existe como literal no fonte (grep §10 limpo).
$content = @"
# dev-env.ps1 — variaveis de ambiente para DEV LOCAL (back) no PowerShell. GERADO por setup-dev.ps1.
# NAO versionar (ignorado via .git/info/exclude). Sem segredo real de prod aqui.
# Uso (ponto+espaco):  . .\dev-env.ps1   ;  ./mvnw spring-boot:run
# A senha do datasource deve casar com o POSTGRES_PASSWORD do seu container (edite se necessario).
`$env:SPRING_DATASOURCE_URL = "$Url"
`$env:SPRING_DATASOURCE_USERNAME = "$User"
`$env:SPRING_DATASOURCE_PASSWORD = "$Pass"
# JWT (SPEC-M1 §3.3): segredo >= 32 bytes gerado em runtime; TTL em segundos. Admin dev vem do Flyway.
`$env:APP_JWT_SECRET = "$Secret"
`$env:APP_JWT_TTL = "$Ttl"
"@
Set-Content -Path $EnvFile -Value $content -Encoding UTF8

if ($Gerado) {
    Write-Host '[setup-dev] APP_JWT_SECRET gerado (>= 32 bytes) e gravado em dev-env.ps1.'
} else {
    Write-Host '[setup-dev] APP_JWT_SECRET ja existente PRESERVADO (idempotente).'
}
Write-Host '[setup-dev] dev-env.ps1 atualizado (datasource + JWT; sem APP_SEED_ADMIN_*).'
Write-Host '[setup-dev] Carregue na sessao atual:  . .\dev-env.ps1'
Write-Host '[setup-dev] Confira:  echo $env:APP_JWT_SECRET'
