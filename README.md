# Remote PhotoBridge

**Android · Wi-Fi Direct · offline · fotografias num único sentido**

Projeto Android independente, v1.0.0-alpha6 (teste de convites Wi-Fi Direct). Um único APK instalado em dois Androids, cada um com um papel definido:

| Papel | Pode fazer | Nunca faz |
|---|---|---|
| **Principal** | Descobrir/ligar por Wi-Fi Direct, mostrar PIN, receber, verificar e gravar fotos na galeria | Enviar fotos |
| **Secundário** | Descobrir Principal, emparelhar e sincronizar as fotos da própria galeria | Receber ou gravar fotos do Principal |

Não precisa de Internet, cartão SIM, dados móveis, conta Google, router, Bluetooth ou serviços Google Play. O `INTERNET` do Android Manifest é necessário **somente para abrir sockets TCP locais** sobre Wi-Fi Direct. Não contém servidores externos nem bibliotecas de telemetria.

## Requisitos

- Dois smartphones com **Wi-Fi Direct** suportado pelo fabricante; Android **8.0 (API 26)** ou superior.
- Wi-Fi ativo nos dois; descoberta de dispositivos pode requerer **Localização** ativa, embora não use localização na app.
- APK instalado nos dois (o Secundário pode ser instalado inicialmente por USB / ADB, sem Internet).
- Principal: espaço livre na galeria; no Android 8/9, autorização de escrita no armazenamento.
- Secundário: permissão para aceder a fotografias. No Android 14+, podes autorizar **todas** ou apenas **fotografias selecionadas**; com acesso parcial, só se transferem as autorizadas.

## Utilização (v1)

1. Instalar o mesmo APK nos dois Androids.
2. Abrir em cada um e escolher o papel persistente: **PRINCIPAL** ou **SECUNDÁRIO**.
3. Em ambos os telemóveis, ligar o **Wi-Fi e a Localização do Android**. O Bluetooth não é necessário.
4. No Principal tocar **ATIVAR RECEÇÃO**. O Android começa a criar um grupo Wi-Fi Direct e este passa a aguardar o Secundário. O PIN temporário não significa que exista uma ligação.
5. No **Secundário**, tocar **PROCURAR DISPOSITIVOS**, selecionar **LIGAR A: [nome do Principal]** (não escolher TVs) e aceitar o convite Wi-Fi Direct no Principal, se aparecer. O Principal já tem o grupo criado e não precisa de procurar o Secundário.
6. Aguardar o estado **LIGADO** em ambos; a lista dos dispositivos já não deve desaparecer durante o toque.
7. No Secundário, introduzir o PIN e tocar primeiro em **TESTAR COMUNICAÇÃO (SEM ENVIAR FOTOS)**. Se indicar TESTE OK, tocar em **SINCRONIZAR FOTOGRAFIAS**.
8. Se a negociação falhar, tocar **DESLIGAR / REINICIAR LIGAÇÃO** nos dois e repetir. No Principal existe ainda **MODO COMPATIBILIDADE · CRIAR GRUPO**, para testar em telemóveis difíceis.
9. O botão **COPIAR DIAGNÓSTICO DA LIGAÇÃO**, no topo de cada app, permite partilhar mensagens e erros de emparelhamento.
6. O Principal guarda os ficheiros exatamente como recebidos em `Pictures/Remote PhotoBridge`, indexados no MediaStore (galeria normal do Android).
10. Repetir a sincronização quando quiser: ficheiros já recebidos (SHA-256) são **ignorados antes de enviar os seus bytes**. Uma interrupção pode ser recuperada repetindo a sincronização.

**Nunca se apagam as fotografias do Secundário.** Não existe funcionalidade para enviar fotos do Principal.

## Segurança, privacidade e integridade

- Wi-Fi Direct usa WPA2 na ligação P2P; aplicação adicionalmente exige PIN temporário do Principal.
- Ligação TCP local **porta 38988**, no endereço Wi-Fi Direct do Principal; não existe cloud.
- Checksum SHA-256 de cada fotografia, calculado no Secundário e validado no Principal após receção.
- Enquanto o ficheiro está a ser recebido, fica pendente/oculto na galeria (Android 10+) e é removido em caso de corrupção/interrupção.
- A cópia é feita em qualidade original, sem conversão nem compressão; os metadados embutidos permanecem nos bytes recebidos.
- Aplicação sem anúncios, sem contas, sem analytics e sem envio de dados para destinos externos.
- Os papéis podem ser alterados pelo utilizador com o botão da aplicação; isso termina a ligação atual.

## Compilação

- Android Studio com JDK 17 e Android SDK 35.
- Gradle **8.10.2**, Android Gradle Plugin **8.8.2**.
- Abrir a pasta do projeto no Android Studio, aguardar a sincronização de Gradle, e executar **Build > Build APK(s)**; ou em consola com Gradle instalado: `gradle assembleDebug`.
- APK de teste gerado em `app/build/outputs/apk/debug/app-debug.apk`.
- O workflow `.github/workflows/android-apk.yml` publica APK de teste em **GitHub Releases** e também em **Actions > Artifacts** ao alterar o código em `main`. Não sobrescreve uma release existente com outra assinatura de debug.
- A chave debug é apenas para testes. Publicação estável requer definir chave de assinatura de release e protegê-la.

## Limitações atuais da versão alpha

- **Código preparado mas ainda não validado em dois telemóveis reais**. A compilação poderá ser verificada pelo GitHub Actions.
- Requer aplicação aberta nos dois equipamentos durante a ligação; não existe sincronização em segundo plano ou arranque automático.
- Sincroniza todas as imagens da galeria às quais o Secundário tenha acesso, não apenas novas da câmara. O Principal descarta as já recebidas antes de transferir os dados.
- Se as fotos forem removidas do Principal **por fora da app**, a base local de hashes continuará a lembrar-se delas; uma futura opção de reparação/reverificação da galeria deve ser acrescentada.
- Compatibilidade Wi-Fi Direct depende de fabricantes, versões de Android e estados das interfaces rádio. Alguns equipamentos obrigam a ativar Localização para descoberta.
- O código de 6 dígitos é adequado para emparelhamento de proximidade com P2P protegido, mas é uma versão inicial: uma versão pública deve acrescentar autenticação mútua robusta e proteção contra tentativas repetidas.
- Se a app for encerrada/recriada durante a transferência, pode ser preciso reabrir e emparelhar de novo.
- Ainda não suporta vídeo, seleção manual por álbum, sincronização programada ou confirmação de espaço livre antes de iniciar a cópia.

## Estrutura

- `MainActivity.java` — seleção de função e UI PT-PT.
- `WifiDirectLink.java` — descoberta/criação de grupo e conexão Wi-Fi Direct.
- `PhotoTransport.java` — TCP local, protocolo, PIN, SHA-256, envio/receção e MediaStore.
- `TransferDb.java` — histórico de hashes recebidos e cache de hashes de origem.
- `.github/workflows/android-apk.yml` — build de APK debug no GitHub.

## Licença

Ainda não definida (projeto inicial, sem licença externa selecionada).

## Histórico de correções

- **alpha6:** o Principal cria um grupo Wi-Fi Direct como proprietário por defeito, com diagnóstico detalhado de proprietário, clientes e estados; teste de comunicação com PIN sem enviar fotografias e botões sem corte.
- **alpha5:** verifica o emparelhamento até 90 s, mostra estados Wi-Fi Direct do Android, separa grupo vazio de cliente realmente ligado e permite consultar o estado em ambos os telemóveis.
- **alpha4:** evita que a lista de equipamentos seja recriada enquanto se toca num botão; feedback visual/tátil; interrompe pesquisa antes de solicitar emparelhamento; acompanha ligação, timeout e erros BUSY; mostra estado no topo; permite reiniciar e copiar diagnóstico.
- **alpha3:** ambos os telemóveis descobrem outros dispositivos, e o Principal escuta num socket local mesmo quando o Android anonimiza o endereço do proprietário do grupo.
- **alpha2:** substitui a janela de seleção inicial por ecrã completo.

**Importante sobre atualização:** o GitHub Actions usa uma chave de debug temporária diferente em cada compilação. Por enquanto, é necessário desinstalar a versão alpha anterior antes de instalar uma nova. Uma assinatura fixa protegida por GitHub Secret está planeada, mas **não está ativa**; evita assumir atualizações por cima da aplicação atual. Desinstalar esta app não apaga as imagens da galeria, mas limpa as suas definições e histórico interno.
