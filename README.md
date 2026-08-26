# Chat Overlay

Android overlay leve para acompanhar o chat público da Kick em tempo real por cima de jogos.

## V0.1

- Conexão nativa ao chat público da Kick.
- Sem Chrome e sem WebView para o chat.
- Overlay Android com permissão `Aparecer sobre outros apps`.
- Arrastar o painel pela barra superior.
- Modo fixado/click-through para os toques passarem ao jogo.
- Até 8 mensagens recentes.
- Mensagens somem após 45 segundos.
- Reconexão automática.
- Serviço em primeiro plano para maior estabilidade durante gameplay.
- Build automático de APK via GitHub Actions.

## Uso

1. Instale o APK de debug gerado pelo workflow **Build Android APK**.
2. Abra o app e libere `Aparecer sobre outros apps`.
3. Informe o nome do canal da Kick, sem `@`.
4. Toque em **Iniciar overlay**.
5. Arraste o chat para a posição desejada.
6. Toque em **Fixar / destravar toques** antes de jogar para que os toques atravessem o painel.

## Estado técnico

O protótipo web anterior validou o chatroom do canal e o recebimento real de mensagens pelo WebSocket/Pusher da Kick. A V0.1 leva esse núcleo para Android nativo com OkHttp.

## Pacote Android

`io.github.astromg01.chatoverlay`

## Custo

O projeto não depende de servidor pago, assinatura ou API paga.
