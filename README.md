# Chat Overlay

Android overlay leve para acompanhar o chat público da Kick em tempo real por cima de jogos.

## V0.3

- Mantém o mesmo núcleo realtime validado nas V0.1/V0.2.
- Conexão nativa ao chat público da Kick, sem Chrome e sem WebView.
- Fundo do painel 100% transparente.
- Prioriza `identity.username_color` e mantém compatibilidade com `identity.color` dos payloads legados.
- Se a Kick não enviar uma cor, o app atribui uma cor estável por username.
- O texto da mensagem recebe uma versão mais clara da cor do usuário para melhorar a leitura.
- Emotes da Kick no formato `[emote:id:nome]` são baixados de `files.kick.com` e renderizados inline.
- Em Android 9+ emotes animados usam `AnimatedImageDrawable` e continuam animados.
- Cache de até ~6 MiB para emotes, evitando downloads repetidos.
- Badges de broadcaster, MOD, VIP, SUB, gifter, verificado, founder e OG viraram ícones vetoriais leves, sem caixas de texto.
- Sombra leve somente no conteúdo para manter leitura sobre cenas claras e escuras.
- Overlay arrastável e posição persistida.
- Modo fixado/click-through para os toques passarem direto ao jogo.
- Até 8 mensagens recentes, removidas automaticamente após 45 segundos.
- Foreground Service persistente com `START_STICKY` e `stopWithTask=false`.
- Notificação contínua com ações para fixar/destravar e parar o overlay.
- Chatroom ID em cache e reconexão com backoff curto.
- Build automático de APK via GitHub Actions.

## Uso

1. Instale o APK gerado pelo workflow **Build Android APK**.
2. Libere `Aparecer sobre outros apps` e notificações.
3. Informe o nome do canal da Kick, sem `@`.
4. Toque em **Iniciar overlay**.
5. Arraste pelo título `KICK CHAT` para posicionar.
6. Fixe os toques antes de jogar. Fixado, o chat continua visível e os comandos passam ao jogo.
7. A tela principal do app pode ser fechada; a notificação mantém o serviço e os controles principais disponíveis.

## Badges

Os eventos de chat da Kick informam o tipo/texto/contagem dos badges, mas não fornecem uma URL de imagem oficial para cada badge. Por isso a V0.3 usa ícones vetoriais próprios e leves para identificar cada função sem depender de assets externos.

## Pacote Android

`io.github.astromg01.chatoverlay`

## Custo

O projeto não depende de servidor pago, assinatura ou API paga.
