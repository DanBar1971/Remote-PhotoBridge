# Assinatura Android persistente — Remote PhotoBridge

**Estado:** código preparado para `v1.0.0-alpha8`. Para o workflow publicar o APK, é necessário configurar **uma única vez** o segredo do GitHub Actions.

## Porquê

Cada runner do GitHub Actions criava anteriormente uma chave debug aleatória. O Android recusava a atualização de APKs com o mesmo `applicationId` mas assinaturas diferentes.

A partir da alpha8, o workflow:
1. Restaura uma chave privada fixa de um **GitHub Actions Repository Secret**.
2. Assina o APK de testes com essa mesma chave em todas as versões futuras.
3. Verifica o SHA-256 do certificado antes de publicar. Se não corresponder, **bloqueia a release**.
4. Nunca inclui o ficheiro privado no código-fonte, nos artifacts nem no APK.

## Configuração uma única vez

No repositório `DanBar1971/Remote-PhotoBridge`:
1. Abrir **Settings → Secrets and variables → Actions → New repository secret**.
2. **Name**: `RPB_ANDROID_SIGNING_BUNDLE`.
3. **Secret**: colar a linha completa do ficheiro privado `RemotePhotoBridge-Secret-NEW-2026.txt` entregue ao responsável do projeto. Este ficheiro é uma cópia Base64 de um tarball com `app/signing/remotephotobridge-alpha.p12` e `local-signing.properties`.
4. Guardar o segredo. Não publicar este conteúdo em commits, issues, screenshots nem comentários.
5. Integrar o PR de assinatura persistente e confirmar a compilação `Build and publish Android APK`.

**Fingerprint do certificado público esperado:**
`cf7b070bc2622c2f48aa71d7d08f9f572dff04d1d5e547b29d201f24c6761f47`

## Atualizações sem desinstalar

- **alpha7 → alpha8**: uma última desinstalação é necessária, porque a antiga versão foi assinada com outra chave debug.
- **alpha8 → alpha9 e seguintes**: instalar diretamente por cima, com a mesma chave, `applicationId` e `versionCode` sempre crescente.
- Não alterar nem regenerar este certificado.
- Guardar cópia privada da chave e das credenciais, fora do GitHub público.

**IMPORTANTE:** a chave anterior de testes foi potencialmente exposta no histórico de uma branch experimental e **não é utilizada** nesta nova configuração. Este certificado foi gerado de raiz para a alpha8.

## Limitações

A configuração altera apenas a **identidade e atualização do APK**. Não corrige automaticamente eventuais problemas de ligação Wi-Fi Direct. A primeira versão alpha8 continua experimental.

Se o segredo estiver ausente, o workflow falha **antes de compilar**, evitando publicar APKs assinados acidentalmente com chaves temporárias.
