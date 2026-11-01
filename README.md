# Remote PhotoBridge — Android 1.0.6

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

**Hoje continua a ser o valor inicial, mas agora existe TODA A GALERIA**, uma escolha explícita que inclui imagens e vídeos de qualquer ano. Quando se escolhe um período de dias, a app filtra as datas por `MediaStore.DATE_TAKEN` (ou `DATE_ADDED` quando ausente), antes da transmissão.

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

**Versão 1.0.6 (versionCode 106)** — compilada com `assembleRelease` (sem debug) e assinada com **a mesma chave privada fixa das versões alpha8–alpha12**. Instala por cima da alpha12 sem desinstalar nem perder a função Principal/Secundário.

O workflow `.github/workflows/android-apk.yml` executa os testes unitários, compila o APK de produção, verifica o SHA-256 do certificado de assinatura e publica `Remote-PhotoBridge-v1.0.6.apk` na release estável `v1.0.6`. Não publica se o certificado não corresponder ou estiver ausente.

Os testes automáticos de datas e compilação não substituem a validação física da abertura das apps Câmara/Galeria em diferentes fabricantes.

Repositório: https://github.com/DanBar1971/Remote-PhotoBridge

## Aspeto: claro, escuro ou automático (1.0.1)

Na aplicação: **Opções → Aspeto**. Escolhe **Claro** (fundos claros e textos escuros), **Escuro** (tema original) ou **Automático → Seguir Android**. A escolha fica guardada nas preferências do respetivo telemóvel, aplica-se ao menu, Partilhar, Opções, suporte, botões, campos, diálogos e barras de estado/navegação. O modo automático acompanha alterações do modo escuro do Android.

A escolha não altera nem reinicia a camada de transferência Nearby. A aplicação evita mudanças de tema durante envios em curso. Para manter o aspeto anterior dos utilizadores existentes, a opção inicial permanece **Escuro** até à escolha manual de outro modo.

Versão de produção, assinatura fixa preservada, **atualização por cima da 1.0.0 sem desinstalar**.

## Atualizações disponíveis (1.0.1)

Em **Opções → Atualizações**, a aplicação mostra a versão instalada, consulta a última release **estável** publicada no GitHub e compara os números de versão. Se houver atualização, aparece **DESCARREGAR ATUALIZAÇÃO**, que abre o APK oficial do GitHub no navegador para instalar por cima, mediante confirmação do Android. A consulta é feita quando se abre Opções pela primeira vez na sessão ou ao tocar em **PROCURAR ATUALIZAÇÕES**. Não descarrega nem instala silenciosamente. A consulta requer Internet, mas a transferência de fotografias por Nearby continua totalmente offline. Em caso de falha de ligação, a app mostra o erro sem interromper a sincronização.

Apenas as versões não experimentais do GitHub Releases são consideradas. A assinatura do APK e o `versionCode` continuam iguais/compatíveis com as versões estáveis anteriores.

## Progresso e notificações (1.0.2)

Em **Partilhar**, os dois Androids apresentam uma barra com a **percentagem real (0–100%)** e quantas fotografias foram confirmadas, tanto ao **enviar** como ao **receber**. O Nearby fornece os bytes efetivamente transferidos para cada fotografia; a percentagem global contabiliza os bytes confirmados e os bytes em curso. Fotografias já existentes são marcadas como processadas sem duplicar. **Os 100% só aparecem quando todas as fotografias foram verificadas e confirmadas.**

O Android mostra também uma notificação discreta de progresso com a percentagem, e uma notificação final **“Fotografias enviadas!”** ou **“Fotografias recebidas!”**, com número de novas e repetidas. Se uma transferência for interrompida, a notificação indica o erro em vez de anunciar conclusão. O modo de notificação funciona sem Internet nem SIM. Em Android 13+ a aplicação solicita a permissão opcional de notificações; recusá-la não impede a barra na app nem a transferência.

Ao regressar ao menu **Partilhar** durante a sessão, a barra conserva o último progresso. Continua a ser uma aplicação que trabalha com os telemóveis ligados; não é prometido um serviço permanente em segundo plano. A versão 1.0.2 mantém o mesmo certificado protegido da 1.0.1 e instala por cima.

## Novo na 1.0.3 — fotos e vídeos sem Internet

- No Secundário, **Partilhar** permite escolher **Fotografias e vídeos** (padrão), **Só fotografias** ou **Só vídeos**, mantendo Hoje (padrão) e últimos 3, 7, 14 ou 30 dias. O número de fotos/vídeos e tamanho total em MB são apresentados antes da confirmação. Nunca envia a galeria inteira.
- A partir do Android 13, o Secundário pede acesso de leitura `READ_MEDIA_VIDEO` além de `READ_MEDIA_IMAGES`; em Android 14+, as permissões podem limitar a pesquisa aos itens escolhidos. No Principal apenas se usam os acessos necessários para gravar no MediaStore.
- Fotos são inseridas em `Pictures/Remote PhotoBridge` e vídeos em `Movies/Remote PhotoBridge`, ambas pastas da biblioteca multimédia do Android. O original é copiado byte a byte e validado por SHA-256. Vídeos são preservados sem compressão nem alteração de resolução; ficheiros já recebidos não são transferidos de novo.
- Cada ficheiro pode ter até 12 GiB, sujeito ao espaço livre disponível, limites do dispositivo e estabilidade do Nearby. Vídeos grandes podem ser significativamente mais lentos, inclusive enquanto a app calcula inicialmente a assinatura de integridade.
- Em caso de queda da ligação, a app reinicia a descoberta/anúncio Nearby automaticamente. É necessário confirmar outra vez os códigos de segurança. No Secundário aparece **Repetir transferência interrompida**, que reenvia a seleção original sem duplicar itens confirmados no Principal.
- Compatibilidade: os dois equipamentos devem instalar 1.0.3 para o envio de vídeos. A ligação com uma versão antiga continua adequada a fotos. O protocolo troca um aviso de capacidades e não envia vídeos a uma versão sem suporte.
- Progresso percentual e notificações continuam a funcionar para ambos os tipos de ficheiro.

A nova versão precisa de validação real em diferentes Androids e com vídeos grandes. Uma ligação Nearby não garante utilizar sempre Wi-Fi; o transporte escolhido automaticamente pode variar conforme o equipamento.

## Novo em 1.0.4 — estabilidade Wi-Fi e diagnóstico de velocidade

Em **Opções → Ligação e Velocidade** escolher:

- **Estável (recomendado, padrão):** envia a preferência `ConnectionType.NON_DISRUPTIVE` ao Google Nearby nos dois lados, para evitar que a negociação altere o Wi-Fi normal do router. Esta proteção pode deixar alguns dispositivos em canais mais lentos (por exemplo Bluetooth), dependendo do hardware. **Não garante impedir o fabricante ou Android de desligar o rádio.**
- **Velocidade máxima (experimental):** usa `ConnectionType.DISRUPTIVE`, dando ao Nearby liberdade para procurar maior débito, mas pode desligar temporariamente a associação Wi-Fi ao router. Selecionar apenas quando se aceita esse efeito.

Configurar o mesmo modo nos dois equipamentos. A preferência só é aplicada ao próximo emparelhamento. Bluetooth e Wi-Fi continuam necessários; nenhuma opção exige Internet.

Ao enviar, a aplicação diferencia **a calcular SHA-256** (leitura integral do ficheiro local, sem tráfego) de **a enviar** (payload Nearby). Durante a transferência apresenta médias em MB/s: *sessão* (inclui espera e cálculos) e *ficheiro* (bytes efetivos pelo Nearby desde o início do ficheiro). Em **Partilhar → Diagnóstico e Opções → Estado do Rádio Wi-Fi**, verifica se o rádio está realmente desligado ou se o Nearby apenas perdeu a ligação ao outro telemóvel.

Existe uma proteção temporária `PARTIAL_WAKE_LOCK` enquanto uma transferência real está ativa, libertada no final ou falha, para reduzir a suspensão da CPU. **Não força o Wi-Fi a ligar e não impede toda a gestão de bateria dos fabricantes.** Para uma comparação justa testar o mesmo ficheiro/posição com Estável vs Velocidade máxima, e anotar a taxa de MB/s e o estado do Wi-Fi após uma falha.

Em **Partilhar**, a escolha dos tipos aparece como **TUDO (fotografias + vídeos)**, **SÓ FOTOGRAFIAS** ou **SÓ VÍDEOS**. É independente da escolha de *Hoje* ou dos últimos 3/7/14/30 dias. A opção Tudo significa ambos os formatos **dentro do intervalo de datas selecionado**, não a galeria histórica inteira.

Conselho de teste: durante transferências grandes, manter ecrã ligado e app visível, desativar restrições de bateria para a Remote PhotoBridge no Android se necessário. A ligação ao router pode ajudar, mas o Nearby escolhe o transporte automaticamente e não garante que todo o tráfego passe pelo router.

## Novidade na 1.0.5 — TODA A GALERIA

No Secundário, em **Partilhar**, o primeiro seletor continua a definir os formatos: **TUDO (fotos + vídeos)**, **SÓ FOTOGRAFIAS** ou **SÓ VÍDEOS**. No segundo seletor — **período** — acrescentou-se, depois de **Últimos 30 dias**, **TODA A GALERIA (sem limite de data)**.

Esta nova escolha pesquisa todos os itens de fotografias e vídeos **indexados e acessíveis ao Android**, sem o filtro de datas que excluía as galerias de um ano ou mais. Para os períodos de Hoje/3/7/14/30 dias, o comportamento anterior mantém-se. **Hoje continua a ser o padrão** sempre que se abre o ecrã.

A pesquisa é executada fora do ecrã principal e mostra previamente **quantidade de fotografias, vídeos e tamanho total em MB**. É obrigatória a confirmação explícita antes de iniciar uma cópia integral, com aviso de consumo de espaço, bateria e duração, e não há eliminação automática de originais. O protocolo aceita lotes até 200 000 ficheiros; acima disso o utilizador deve selecionar períodos menores. Fotos e vídeos repetidos continuam identificados pelo SHA-256. No Principal, as imagens são guardadas em Pictures/Remote PhotoBridge e os vídeos em Movies/Remote PhotoBridge.

**Atenção aos acessos:** no Android 14+, uma permissão apenas para fotografias selecionadas não permite catalogar toda a biblioteca. Para esta opção a app solicita permissão de leitura total para os tipos selecionados; se continuar parcial, direciona para as permissões nas Definições. Fotografias/vídeos ainda não indexados, mantidos apenas na nuvem ou com ficheiros inacessíveis não são transferidos. Ficheiros individuais têm o limite técnico atual de 12 GiB.

Esta atualização 1.0.5 continua a usar Nearby Connections, as opções de rede Estável/Rápida e a assinatura de produção fixa da versão anterior, permitindo atualizar diretamente por cima.

## Novo em 1.0.6 — MB/GB antes e durante a transferência

**Antes de enviar:** a previsão da galeria passa a apresentar separadamente **Fotografias: quantidade e tamanho**, **Vídeos: quantidade e tamanho**, **TOTAL: quantidade e tamanho**, sempre em MB ou GB. O resumo fica visível no ecrã Partilhar e aparece novamente na confirmação de envio. Os valores derivam de MediaStore sem comprimir nem ler integralmente o conteúdo.

**Durante o envio e a receção:** além de 1/19 ficheiros e da percentagem, a aplicação mostra **Lote: 650 MB de 2,00 GB; faltam 1,35 GB** e **Ficheiro 3/19: video.mp4; ficheiro atual 150 MB de 500 MB; faltam 350 MB** (exemplos). Os valores provêm dos bytes reais reportados pelo Nearby e refrescam até a cada 400 ms, mesmo quando a percentagem inteira não se altera. Mantêm-se a velocidade de transferência, a leitura SHA-256, o aviso de conclusão e o reconhecimento de duplicados.

A contagem de bytes globais **inclui ficheiros repetidos confirmados**, mesmo quando já existem no Principal e não precisam de ser transferidos outra vez. Representa o volume do lote já processado e o que falta processar, não necessariamente o tráfego efetivo pela rede. Cada ficheiro transferido mostra separadamente os bytes transmitidos e os que faltam. MB/GB são unidades decimais (1 MB = 1 000 000 bytes).

Mantém a mesma assinatura de produção para atualizar a v1.0.5 diretamente por cima sem desinstalar.

## Ajuste adicional de desempenho 1.0.6

A negociação Nearby tem três modos documentados: **Estável** (NON_DISRUPTIVE), **Equilibrado** (BALANCED) e **Velocidade máxima** (DISRUPTIVE). Estável não altera Wi-Fi/Bluetooth mas pode limitar a largura de banda em alguns Androids. Equilibrado permite alterar a ligação apenas se necessário, procurando canais rápidos para vídeo. Velocidade máxima pode desligar temporariamente o Wi-Fi do router. A app mantém o modo Estável anterior até o utilizador selecionar outro; em ambos os telemóveis deve ser escolhido o mesmo modo e confirmado um novo emparelhamento.

Em lotes com vídeos de pelo menos 200 MB e modo Estável, a confirmação alerta para um possível gargalo. **Durante o cálculo SHA-256**, a app mostra quantos bytes locais já analisou sem contabilizá-los como enviados. A transmissão via Nearby inicia apenas após o cálculo do hash. O desempenho depende do transporte e do hardware e estas alterações não garantem acelerar todos os pares de telemóveis.
