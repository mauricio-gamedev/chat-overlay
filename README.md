# Chat Overlay

Android overlay leve para acompanhar o chat público da Kick em tempo real por cima de jogos.

## V0.2

- Mantém o mesmo núcleo realtime validado na V0.1.
- Conexão nativa ao chat público da Kick, sem Chrome e sem WebView.
- Fundo do painel 100% transparente.
- Cor real do nome recebida da identidade da Kick (`color` / `username_color`).
- Badges compactos para broadcaster, MOD, VIP, SUB, gifter, verificado, founder e OG quando enviados no payload.
- Sombra leve somente no texto para manter leitura sobre cenas claras e escuras.
- Overlay arrastável e posição persistida.
- Modo fixado/click-through para os toques passarem direto ao jogo.
- Até 8 mensagens recentes, removidas automaticamente após 45 segundos.
- Foreground Service persistente com `START_STICKY` e `stopWithTask=false`.
- Notificação contínua com ações para fixar/destravar e parar o overlay.
- Estado salvo para o serviço conseguir se reconstruir se o Android recriar o processo.
- Chatroom ID em cache para evitar refazer chamadas HTTP a cada reconexão.
- Reconexão com backoff curto e limite de 10 segundos.
- Proteção contra callbacks de sockets antigos durante uma reconexão.
- Build automático de APK via GitHub Actions.

## Uso

1. Instale o APK gerado pelo workflow **Build Android APK**.
2. Libere `Aparecer sobre outros apps` e notificações.
3. Informe o nome do canal da Kick, sem `@`.
4. Toque em **Iniciar overlay**.
5. Arraste pelo título `KICK CHAT` para posicionar.
6. Fixe os toques antes de jogar. Fixado, o chat continua visível e os comandos passam ao jogo.
7. Depois disso a tela principal do app pode ser fechada; a notificação mantém os controles principais disponíveis.

## Pacote Android

`io.github.astromg01.chatoverlay`

## Custo

O projeto não depende de servidor pago, assinatura ou API paga.
