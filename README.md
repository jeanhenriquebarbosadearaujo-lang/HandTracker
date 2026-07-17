# 🖐️ Contador de Dedos (Hand Tracker)

App Android (Kotlin) que usa a **câmera traseira** + **MediaPipe Hand Landmarker** do Google para detectar mãos em tempo real e **contar quantos dedos estão levantados**.

A detecção é feita com IA (21 pontos por mão), então funciona com fundos variados — diferente de soluções que dependem só de cor de pele.

---

## ✨ O que o app faz
- Mostra o preview da câmera ao vivo.
- Desenha o esqueleto da mão (21 pontos) sobre o vídeo.
- Mostra em destaque, no topo, **quantos dedos estão levantados** (até 2 mãos = até 10 dedos).
- Na primeira execução ele baixa o modelo `hand_landmarker.task` (~8 MB), por isso precisa de internet na 1ª vez.

---

## 📦 Como pegar o APK (pelo GitHub Actions)

1. Suba ESTA pasta inteira para o seu repositório GitHub (chamado, por exemplo, `HandTracker`).
2. O arquivo `.github/workflows/build-apk.yml` dispara o build sozinho a cada *push*.
3. Vá na aba **Actions** → clique na execução que ficou verde → role até **Artifacts** → baixe **`hand-tracker-apk`**.
4. Descompacte: dentro vem o `app-debug.apk`. Instale no celular.

> Obs.: o APK é de **debug** (não assinado para loja). Para instalar, autorize "fontes desconhecidas" no Android.

---

## 💻 Como abrir/editar no Android Studio
1. Abra a pasta `HandTracker` no Android Studio.
2. O Studio vai **gerar o Gradle Wrapper automaticamente** (`gradlew` + `gradle-wrapper.jar`) — o projeto já vem com o `gradle-wrapper.properties` indicando a versão 8.9.
3. Aguarde o *Gradle Sync* terminar (ele baixa as dependências).
4. Rode em ▶️ ou gere o APK em **Build → Build APK(s)**.

---

## 🔧 Versões usadas
| Item | Versão |
|------|--------|
| Android Gradle Plugin | 8.5.2 |
| Gradle | 8.9 |
| Kotlin | 1.9.24 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 (Android 8.0+) |
| CameraX | 1.3.4 |
| MediaPipe tasks-vision | 0.10.14 |

---

## 🔐 Permissões
- **CAMERA** — para o preview e a detecção.
- **INTERNET** — para baixar o modelo na 1ª execução.

---

## 🗂️ Estrutura do projeto
```
HandTracker/
├── .github/workflows/build-apk.yml   # CI que gera o APK
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/handtracker/
│       │   ├── MainActivity.kt        # câmera + detecção + contagem
│       │   └── OverlayView.kt         # desenha os pontos da mão
│       └── res/                        # layouts, strings, cores, tema, ícone
```

---

## 🧠 Como a contagem funciona
O MediaPipe retorna 21 pontos por mão. Para cada dedo:

- **Indicador, médio, anelar e mínimo:** o dedo está "levantado" quando a **ponta** está acima da **junta do meio (PIP)** (coordenada `y` menor, pois o eixo Y cresce para baixo).
- **Polegar:** está "levantado" quando a ponta está mais afastada (no eixo X) da base do mínimo do que a junta IP.

Funciona melhor com a **mão na vertical e os dedos apontando para cima**.

---

## ⚠️ Observações
- Requer **Android 8.0 (API 26)** ou superior.
- Para publicar na **Play Store** seria preciso gerar um APK/AAB **assinado** (com keystore) — este projeto gera apenas o de debug.
- A iluminação e o fundo ajudam: mãos bem iluminadas são detectadas com mais precisão.
