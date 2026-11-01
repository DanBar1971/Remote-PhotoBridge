# Remote PhotoBridge — Android 1.0.1

Aplicação Android para **fotografar, consultar a Galeria e transferir fotos locais** entre dois telemóveis, sem Internet, SIM, conta Google para sincronização ou ligação ao mesmo router.

## Menu principal

- **CÂMARA:** abre a aplicação Câmara normal do telemóvel em modo fotografia. Permite tirar **várias fotografias seguidas**, guardadas pela própria Câmara na Galeria do aparelho (normalmente DCIM/Camera), sem ficheiros temporários geridos pela PhotoBridge. Ao voltar, no Secundário, a app oferece abrir Partilhar para sincronizar as fotos de **Hoje**, sem as enviar automaticamente.
- **GALERIA:** abre a aplicação Galeria normal do Android (a página da biblioteca, **não** apenas a última fotografia). Possui modos alternativos caso a versão do Android não anuncie a categoria de Galeria.
- **PARTILHAR:** mostra receção/envio Nearby com as opções de período por data. O Principal **só recebe** e o Secundário **só envia**. O Principal recebe as imagens originais em `Pictures/Remote PhotoBridge` pela API `MediaStore`; estas aparecem na Galeria. O botão da Galeria no ecrã de receção também abre agora a Galeria normal.
- **OPÇÕES:** escolher Master/Slave (Principal/Secundário), consultar acessos, gerir permissões diretamente nas definições Android, Acerca, apoiar o autor e contactar suporte.

## Partilhar fotografias (Nearby Connections)

1. Na primeira utilização, selecionar **Principal** no equipamento recetor e **Secundário** no equipamento emissor, em **Opções**.
2. Em **Partilhar**, no Principal tocar **RECEBER FOTOGRAFIAS**.
3. No Secundário abrir **Partilhar → PROCURAR PRINCIPAL**, selecionar o outro Android e confirmar **o mesmo código de segurança nos dois aparelhos**.
4. No Secundário escolher as fotos de **Hoje (padrão), últimos 3, 7, 14 ou 30 dias**; usar **VER FOTOS DESTE PERÍODO** para conferir número/tamanho; depois **SINCRONIZAR FOTOGRAFIAS** e confirmar.
5. O Principal grava originais na Galeria, sem apagar os originais do Secundário. Imagens repetidas por SHA-256 são ignoradas.

O transporte utiliza Google Nearby Connections (como a SaúdePrática), que gere automaticamente Bluetooth e Wi-Fi sem ligação ao router. Requer os serviços Google Play instalados e as permissões necessárias, mas não dados móveis.

**Não existe opção de enviar toda a galeria.** As datas são filtradas no Secundário por `MediaStore.DATE_TAKEN` (ou `DATE_ADDED` quando ausente), antes da transmissão.

## Câmara e Galeria

- A Câmara é **nativa** e independente da app. A PhotoBridge não escolhe a localização nem cria antecipadamente o ficheiro da fotografia: isso fica ao cuidado da aplicação Câmara do fabricante. Desta forma, é possível tirar várias fotos e encontrá-las na sua Galeria habitual.
- A Galeria também é a **aplicação do sistema**. O atalho abre a biblioteca inteira; a PhotoBridge não a substitui por um visualizador da última foto.
- O próprio Android gere as permissões de utilização da Câmara externa. A leitura de fotografias para enviar requer autorização à PhotoBridge no Secundário; a página **Opções → Acessos e permissões** ajuda a pedi-la e alterar os direitos.
- Uma ligação Nearby ativa pode continuar enquanto se regressa ao menu, mas o utilizador deve manter os aparelhos desbloqueados durante transferências grandes.

## Acerca e Apoio

Autor: **Daniel Barradas**. Suporte: **daniel.barradas@gmail.com**, com formulário de dúvida, sugestão e erro semelhante ao da SaúdePrática, abrindo a aplicação de email sem enviar por conta própria.

Apoio opcional:
- Buy Me a Coffee: https://buymeacoffee.com/danielbarr7
- PayPal: https://www.paypal.com/paypalme/djjindungo

Não existem pagamentos dentro da aplicação.

## APK de produção e atualizações

**Versão 1.0.1 (versionCode 101)** — compilada com `assembleRelease` (sem debug) e assinada com **a mesma chave privada fixa das versões alpha8–alpha12**. Instala por cima da alpha12 sem desinstalar nem perder a função Principal/Secundário.

O workflow `.github/workflows/android-apk.yml` executa os testes unitários, compila o APK de produção, verifica o SHA-256 do certificado de assinatura e publica `Remote-PhotoBridge-v1.0.1.apk` na release estável `v1.0.1`. Não publica se o certificado não corresponder ou estiver ausente.

Os testes automáticos de datas e compilação não substituem a validação física da abertura das apps Câmara/Galeria em diferentes fabricantes.

Repositório: https://github.com/DanBar1971/Remote-PhotoBridge

## Aspeto: claro, escuro ou automático (1.0.1)

Na aplicação: **Opções → Aspeto**. Escolhe **Claro** (fundos claros e textos escuros), **Escuro** (tema original) ou **Automático → Seguir Android**. A escolha fica guardada nas preferências do respetivo telemóvel, aplica-se ao menu, Partilhar, Opções, suporte, botões, campos, diálogos e barras de estado/navegação. O modo automático acompanha alterações do modo escuro do Android.

A escolha não altera nem reinicia a camada de transferência Nearby. A aplicação evita mudanças de tema durante envios em curso. Para manter o aspeto anterior dos utilizadores existentes, a opção inicial permanece **Escuro** até à escolha manual de outro modo.

Versão de produção, assinatura fixa preservada, **atualização por cima da 1.0.0 sem desinstalar**.
