# Remote PhotoBridge — Android

Transferência local de fotografias originais do telemóvel **Secundário (só envia)** para o **Principal (só recebe)**, sem Internet, sem SIM, sem conta cloud e sem necessidade de ambos estarem ligados ao mesmo router.

## Fluxo da alpha12

Nesta versão, a ligação foi migrada do controlo Wi-Fi Direct manual (que falhava nos testes Redmi) para **Google Nearby Connections**: o mesmo sistema que funciona na sincronização da SaúdePrática. Utiliza Bluetooth e Wi-Fi automaticamente, sem dados móveis. Necessita de Google Play Services instalado e atualizado. **A biblioteca não garante forçar exclusivamente o transporte Wi-Fi Direct.**

1. Instalar a mesma APK em ambos (desde a alpha8, a assinatura é fixa e aceita atualização por cima).
2. Principal: **RECEBER FOTOGRAFIAS**, fica visível por Nearby.
3. Secundário: **PROCURAR PRINCIPAL**, tocar no telemóvel que anuncia Remote PhotoBridge.
4. Comparar os códigos de segurança nos **dois telemóveis** e confirmar apenas se forem iguais.
5. Secundário: escolher o período:
   - **Hoje** (valor inicial): desde a meia-noite local.
   - **Últimos 3 dias**, **7 dias**, **14 dias** ou **30 dias**, incluindo hoje.
6. **VER FOTOS DESTE PERÍODO** para conhecer a quantidade, depois **SINCRONIZAR FOTOGRAFIAS** e confirmar tamanho e número.
7. O Principal guarda cada imagem validada por SHA-256 na Galeria, em `Pictures/Remote PhotoBridge`. Os originais continuam no Secundário; fotos com hash já recebido não são guardadas de novo.

### Política de datas

- **Nunca existe opção de transferir toda a galeria.**
- O filtro é aplicado no Secundário **antes de calcular os hashes ou enviar ficheiros**.
- Usa `MediaStore.Images.Media.DATE_TAKEN` em milissegundos; apenas quando ausente, usa `DATE_ADDED` em segundos. Datas são convertidas para os dias do fuso horário local.
- Se a data de uma imagem estiver errada nos metadados Android, pode cair fora do intervalo; não envia imagens mais antigas por acidente.
- Inclui fotografias a que a aplicação tem acesso. No Android com acesso limitado à galeria, só envia imagens explicitamente autorizadas.

### Galeria do Principal

As imagens recebidas são inseridas por `MediaStore` em `Pictures/Remote PhotoBridge`, com o `MIME_TYPE`, `DATE_TAKEN` e `IS_PENDING` atualizados. O sistema Android indexa-as para Galeria/Google Fotos local. São copiadas em qualidade original, byte a byte; só ficam visíveis após SHA-256 corresponder. Transferências incompletas não ficam publicadas. Em versões antigas do Android usa a digitalização multimédia.

### Segurança e sentido único

- Só o Secundário chama `sendPhotos` e lê a sua galeria.
- Só o Principal aceita e grava payloads FILE.
- A autenticação Nearby mostra dígitos iguais nos dois dispositivos, que têm de ser confirmados explicitamente.
- A descoberta é limitada ao identificador específico do serviço Remote PhotoBridge: televisões e outras apps não surgem.
- A app não precisa de acesso à Internet para a troca, mas depende da presença prévia dos serviços Google Play.
- Sem eliminação automática de fotografias em nenhum dos equipamentos.

## Testes de desenvolvimento

`PhotoDateRangeTest` confirma Hoje, últimos três dias, passagem de mês e mudança de hora e que um pedido “toda a galeria” não é permitido.

O workflow `.github/workflows/validate-android-pr.yml` executa testes e compilação antes do merge. Depois de integrar na branch principal, `.github/workflows/android-apk.yml` compila com a **chave privada fixa**, verifica a assinatura SHA-256 e publica `Remote-PhotoBridge-v1.0.0-alpha12-debug.apk` numa pré-release.

**Importante:** testes automáticos/compilação não substituem um teste real de emparelhamento, transferência, deduplicação e visualização na galeria nos dois telemóveis. Esta é uma versão alpha.

## Repositório

https://github.com/DanBar1971/Remote-PhotoBridge

Chave de assinatura privada guardada em GitHub Actions Secret `RPB_ANDROID_SIGNING_BUNDLE`. Não publicar ficheiros de assinatura no código-fonte.

## Novidades alpha12 — Câmara e Galeria

- **Principal (apenas recetor):** assim que a aplicação confirma que uma nova imagem foi gravada corretamente, aparece o botão **MOSTRAR GALERIA**. Abre a fotografia recebida numa aplicação de Galeria/visualizador do Android, com fallback para a Galeria normal.
- **Secundário (apenas emissor):** novo botão **ABRIR CÂMARA**. É aberta a aplicação de câmara instalada, com captura de resolução original para um item MediaStore em `DCIM/Remote PhotoBridge`.
- Após a captura, o original fica guardado primeiro na **Galeria do Secundário**; a aplicação pergunta **“Sincronizar apenas esta fotografia?”**. Nada é enviado sem confirmação.
- Se já existir uma ligação Nearby, só essa fotografia é enviada; se estiver desligado, é possível procurar o Principal e confirmar novamente quando a ligação for estabelecida.
- Se o utilizador escolher **Mais tarde**, a imagem mantém-se na galeria e poderá entrar numa futura sincronização por período.
- O Principal continua proibido de enviar fotografias; o sentido da comunicação e a deduplicação por SHA-256 mantêm-se.
- O certificado protegido e o identificador Android continuam iguais: instalar `alpha12` sobre `alpha11`, sem desinstalar.

A abertura da câmara e o visualizador da Galeria dependem das apps instaladas em cada Android. É necessário confirmar com testes físicos nos dois equipamentos.
