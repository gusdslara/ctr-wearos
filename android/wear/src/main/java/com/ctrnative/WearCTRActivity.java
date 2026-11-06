package com.ctrnative;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * WearCTRActivity - Atividade principal de Crash Team Racing para Wear OS:
 *
 * 1. Duas Opções de Zoom (alternadas pela Coroa / Bezel rotatório):
 *    - Opção 0 (Padrão): Tela Cheia - preenche 100% da tela redonda sem distorção
 *      (imagem 4:3 recortada no centro; bordas laterais/cantos são cortados).
 *    - Opção 1: Tela Quadrada / Original inscrita no círculo (4:3 sem distorção).
 *
 * 2. Interface Minimalista Sony PlayStation (Sem D-Pad na tela):
 *    - Cima: Triângulo (△) | Baixo: X (✕) | Esquerda: Quadrado (□) | Direita: Círculo (○)
 *    - 4 Cantos Diagonais: L1 (Sup Esq), R1 (Sup Dir), L2 (Inf Esq), R2 (Inf Dir)
 *    - 2 toques com 2 dedos = SELECT (com banner central que surge e some)
 *    - 3 toques com 2 dedos = START  (com banner central que surge e some)
 *    - Feedback visual minimalista apenas na ativação (arcos coloridos Sony na borda circular).
 *
 * 3. Direção Precisa por Giroscópio + Acelerômetro com Calibração Dinâmica de Postura:
 *    - Olhando para o relógio com o pulso erguido (gesto normal):
 *      * Calibra automaticamente a postura natural do pulso (gx/gy neutros) e recalibra quando você
 *        toca para acelerar (X), garantindo que a posição atual do seu braço seja sempre o centro reto!
 *      * Combina os 2 gestos naturais do pulso erguido: girar o antebraço (pronation/supination) +
 *        virar como volante no plano da tela (yaw/twist) + giroscópio para resposta imediata.
 *      * Pré-compensa a zona morta interna da física do CTR (0x30 = 48 em VehPhysJoystick.c),
 *        entregando direção analógica proporcional, suave e precisa desde pequenos movimentos do pulso,
 *        sem travar a curva no máximo com BTN_LEFT/BTN_RIGHT.
 *    - Modo Pulseira / Wii Wheel (retirando o relógio do braço e segurando nas pulseiras na horizontal):
 *      * Ajusta automaticamente a orientação dos botões e sensores.
 *    - Pulo do kart (R1) com tilt de pulso calibrado (filtro de gravidade linear + giroscópio).
 *
 * 4. Menus (menu principal, pausa, resultados, loading): direção, acelerar por tilt e pulo por tilt
 *    ficam DESLIGADOS. A navegação usa o D-Pad invisível no centro da tela (WearTouchOverlay).
 */
public class WearCTRActivity extends SDLActivity implements SensorEventListener {

    private static final String TAG = "WearCTRActivity";

    private WearTouchOverlay touchOverlay;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;

    // Transferência Wi-Fi se não houver asset
    private RomInbox romInbox;
    private RomTransferView transferView;

    // Controle de Zoom via Bezel / Coroa (Apenas 2 opções: 0 = Round Full / Zoom In, 1 = Square 4:3 / Zoom Out)
    private int currentZoomMode = 0; // Inicia em 0 (Whole Round Screen)
    private float rotaryAccum = 0f;
    private long lastRotaryTime = 0;
    private static final float ROTARY_STEP = 1.35f; // Exige giro deliberado (evita disparos acidentais ao encostar)
    private static final long ROTARY_ACCUM_TIMEOUT_MS = 600; // Tempo máximo para acumular passos da coroa

    // Touch Guard: impede zoom acidental enquanto joga ou desliza dedos entre botões
    private boolean isFingerTouching = false;
    private long lastTouchActivityTime = 0;
    private static final long WHEEL_TOUCH_GUARD_MENU_MS = 600;
    private static final long WHEEL_TOUCH_GUARD_GAME_MS = 1500; // 1.5s de guarda durante o jogo (evita zoom ao trocar X/Bola)

    // Filtro de gravidade e sensores
    private boolean gravityInitialized = false;
    private float gravX = 0f, gravY = 3.8f, gravZ = 9.0f;
    private float smoothSteerX = 0f;
    private float smoothPitchY = 0f;
    private float lastGyroX = 0f, lastGyroY = 0f, lastGyroZ = 0f;

    // Baseline adaptativa da postura natural do pulso erguido (olhando para o relógio)
    private boolean baselineInitialized = false;
    private float baseGx = 0f;
    private float baseGy = 3.8f;
    private float baseGz = 8.8f;

    // Detecção de Pulo por Gesto de Braço / Relógio ("poin" subida e descida)
    private long lastJumpTime = 0;
    private static final long JUMP_COOLDOWN_MS = 480;

    // Estado da máquina de reconhecimento do gesto de "poin" (subida e descida rápida do antebraço):
    // 0 = repouso / dirigindo
    // 1 = subida detectada (impulso rápido para cima sem esterçamento brusco)
    private int jumpGesturePhase = 0;
    private long jumpPhaseStartTime = 0;
    private float peakUpAcc = 0f;

    // Estado de menu vindo do jogo (gameMode1 / ptrActiveMenu)
    private boolean inMenu = true;
    private long menuExitTime = 0;
    private static final long POST_MENU_SENSOR_GRACE_MS = 600;

    // Orientação: sempre no modo pulso erguido (orientação padrão)
    private static final int wheelOrientation = 0;

    public static void pickFile() {
        // No-op on Wear OS (handled by RomInbox)
    }

    public static boolean isPickerActive() {
        return false;
    }

    public static String getStoredAssetPath() {
        try {
            android.app.Activity activity = (android.app.Activity) SDLActivity.getContext();
            if (activity != null) {
                File intAssets = new File(activity.getFilesDir(), "assets");
                if (new File(intAssets, "ctr-u.bin").exists() || new File(intAssets, "BIGFILE.BIG").exists()) {
                    return intAssets.getAbsolutePath();
                }
                File ext = activity.getExternalFilesDir(null);
                if (ext != null) {
                    File extAssets = new File(ext, "assets");
                    return extAssets.getAbsolutePath();
                }
                return intAssets.getAbsolutePath();
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "SDL3",
            "ctr_native"
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        super.onCreate(savedInstanceState);

        initSensors();
        setupTouchOverlay();
        checkAssetsAndRomInbox();
    }

    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            }
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (gyroscope != null) {
                sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
            }
        }
    }

    private void setupTouchOverlay() {
        final ViewGroup rootView = getWindow().getDecorView().findViewById(android.R.id.content);
        if (rootView != null) {
            touchOverlay = new WearTouchOverlay(this);
            rootView.addView(touchOverlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            touchOverlay.post(() -> applyDisplayRotationForOrientation(wheelOrientation));
        }
    }

    /**
     * Recalibra suavemente a postura neutra do pulso quando o usuário inicia a aceleração (segura X),
     * garantindo que o ângulo natural em que ele está olhando para o relógio seja o centro (direção reta).
     */
    public void notifyThrottleEngaged() {
        if (gravityInitialized && wheelOrientation == 0) {
            // Se o pulso estiver numa postura plausível de olhar para o relógio, aproxima o centro neutro
            if (Math.abs(gravX) < 3.5f && gravY > -1.5f && gravY < 7.5f) {
                baseGx = 0.65f * baseGx + 0.35f * gravX;
                baseGy = 0.50f * baseGy + 0.50f * gravY;
                baseGz = 0.50f * baseGz + 0.50f * gravZ;
            }
        }
    }

    private File getGameAssetsDir() {
        File intAssets = new File(getFilesDir(), "assets");
        if (new File(intAssets, "ctr-u.bin").exists() || new File(intAssets, "BIGFILE.BIG").exists()) {
            return intAssets;
        }
        File ext = getExternalFilesDir(null);
        File root = (ext != null) ? ext : getFilesDir();
        return new File(root, "assets");
    }

    private boolean hasRetailAssets() {
        File intAssets = new File(getFilesDir(), "assets");
        if (new File(intAssets, "ctr-u.bin").exists() || new File(intAssets, "BIGFILE.BIG").exists()) return true;

        File assetsDir = getGameAssetsDir();
        if (new File(assetsDir, "ctr-u.bin").exists()) return true;
        if (new File(assetsDir, "BIGFILE.BIG").exists()) return true;

        File ext = getExternalFilesDir(null);
        if (ext != null) {
            if (new File(ext, "ctr-u.bin").exists()) return true;
            if (new File(ext, "BIGFILE.BIG").exists()) return true;
        }
        return false;
    }

    private void checkAssetsAndRomInbox() {
        if (!hasRetailAssets()) {
            final ViewGroup rootView = getWindow().getDecorView().findViewById(android.R.id.content);
            if (rootView == null) return;

            File assetsDir = getGameAssetsDir();
            if (!assetsDir.exists()) assetsDir.mkdirs();

            transferView = new RomTransferView(this);
            rootView.addView(transferView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));

            romInbox = new RomInbox(this, assetsDir, new RomInbox.Listener() {
                @Override
                public void onServerReady(String url, android.graphics.Bitmap qrBitmap) {
                    if (transferView != null) {
                        transferView.setQrCode(url, qrBitmap);
                    }
                }

                @Override
                public void onProgress(int percent) {
                    if (transferView != null) {
                        transferView.setProgress(percent);
                    }
                }

                @Override
                public void onFileReceived(File file) {
                    if (transferView != null) {
                        transferView.setStatus("Asset CTR recebido! Reiniciando...", android.graphics.Color.rgb(46, 213, 115));
                    }
                    if (romInbox != null) {
                        romInbox.stop();
                    }
                    rootView.postDelayed(() -> {
                        Intent intent = getIntent();
                        finish();
                        startActivity(intent);
                    }, 1200);
                }

                @Override
                public void onError(String msg) {
                    if (transferView != null) {
                        transferView.setStatus(msg, android.graphics.Color.rgb(255, 71, 87));
                    }
                }
            });
            romInbox.start();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        long now = System.currentTimeMillis();
        lastTouchActivityTime = now;
        rotaryAccum = 0f; // Qualquer toque ou deslize na tela zera imediatamente o acumulador da coroa!

        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            isFingerTouching = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            isFingerTouching = false;
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            isFingerTouching = (ev.getPointerCount() > 1);
        }

        return super.dispatchTouchEvent(ev);
    }

    /**
     * Aplica o zoom de forma estritamente direcional (NÃO-circular):
     * - Modo 0: TELA CHEIA (Zoom In)  -> alcançado somente girando para frente / horário (delta > 0)
     * - Modo 1: TELA QUADRADA (Zoom Out) -> alcançado somente girando para trás / anti-horário (delta < 0)
     * Se o usuário continuar girando na mesma direção além do limite, permanece no modo e NUNCA inverte.
     */
    private void setZoomMode(int targetMode) {
        int newMode = (targetMode <= 0) ? 0 : 1;
        if (newMode == currentZoomMode) {
            // Já está no modo desejado: não faz nada (não é circular!)
            return;
        }
        currentZoomMode = newMode;
        try {
            WearTouchOverlay.nativeSetWearZoomMode(currentZoomMode);
        } catch (UnsatisfiedLinkError | Exception ignored) {}

        if (touchOverlay != null) {
            String label = (currentZoomMode == 0) ? "ZOOM IN (TELA CHEIA)" : "ZOOM OUT (TELA QUADRADA)";
            int color = (currentZoomMode == 0) ? WearTouchOverlay.COLOR_CROSS : WearTouchOverlay.COLOR_TRIANGLE;
            touchOverlay.showCenterBanner(label, color, 850);
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL
                && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            long now = System.currentTimeMillis();
            long guardMs = inMenu ? WHEEL_TOUCH_GUARD_MENU_MS : WHEEL_TOUCH_GUARD_GAME_MS;

            // Bloqueio rigoroso contra confusão com toque/jogabilidade:
            // 1. Dedo encostado na tela agora (deslizando de X para Bola, segurando X, etc.)
            // 2. Toque recente (últimos 1.5s em gameplay ou 600ms em menus)
            // 3. Qualquer botão ativo ou gesto no overlay
            if (isFingerTouching
                    || (now - lastTouchActivityTime) < guardMs
                    || (touchOverlay != null && touchOverlay.isTouchGuarded(guardMs))) {
                rotaryAccum = 0f;
                return true;
            }

            // Descarta acúmulos defasados no tempo
            if (now - lastRotaryTime > ROTARY_ACCUM_TIMEOUT_MS) {
                rotaryAccum = 0f;
            }
            lastRotaryTime = now;

            float delta = event.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (delta != 0f) {
                // Direcional estrito: inverter o sentido do giro cancela acúmulos na direção oposta
                if ((delta > 0 && rotaryAccum < 0) || (delta < 0 && rotaryAccum > 0)) {
                    rotaryAccum = 0f;
                }
                rotaryAccum += delta;

                // Giro para frente/cima (horário, delta > 0) -> APENAS Zoom In (Tela Cheia, modo 0)
                if (rotaryAccum >= ROTARY_STEP) {
                    rotaryAccum = 0f;
                    setZoomMode(0);
                    return true;
                }
                // Giro para trás/baixo (anti-horário, delta < 0) -> APENAS Zoom Out (Tela Quadrada, modo 1)
                else if (rotaryAccum <= -ROTARY_STEP) {
                    rotaryAccum = 0f;
                    setZoomMode(1);
                    return true;
                }
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            lastGyroX = event.values[0];
            lastGyroY = event.values[1];
            lastGyroZ = event.values[2];
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float ax = event.values[0];
            float ay = event.values[1];
            float az = event.values[2];

            if (!gravityInitialized) {
                gravX = ax;
                gravY = ay;
                gravZ = az;
                gravityInitialized = true;
            }

            // Filtro passa-baixa rápido (alpha = 0.72f) para separar gravidade sem introduzir atraso na direção
            final float alpha = 0.72f;
            gravX = alpha * gravX + (1f - alpha) * ax;
            gravY = alpha * gravY + (1f - alpha) * ay;
            gravZ = alpha * gravZ + (1f - alpha) * az;

            if (!baselineInitialized) {
                baseGx = Math.max(-2.0f, Math.min(2.0f, gravX));
                baseGy = Math.max(1.0f, Math.min(6.0f, gravY));
                baseGz = Math.max(5.0f, Math.min(9.5f, gravZ));
                baselineInitialized = true;
            }

            float linX = ax - gravX;
            float linY = ay - gravY;
            float linZ = az - gravZ;

            updateMenuState();
            handleTiltJump(linX, linY, linZ);
            handleSteeringAndDpad(gravX, gravY, gravZ);
        }
    }

    /**
     * Lê do jogo se estamos num menu e liga/desliga os controles de movimento do relógio.
     */
    private void updateMenuState() {
        boolean nowInMenu;
        try {
            nowInMenu = WearTouchOverlay.nativeIsInMenu();
        } catch (UnsatisfiedLinkError | Exception e) {
            nowInMenu = false;
        }
        if (nowInMenu == inMenu) {
            return;
        }
        inMenu = nowInMenu;
        if (!inMenu) {
            menuExitTime = System.currentTimeMillis();
            // Recomeça os filtros do zero para não sair do menu já acelerando/virando
            smoothSteerX = 0f;
            smoothPitchY = 0f;
        }
        jumpGesturePhase = 0;
        peakUpAcc = 0f;
        if (touchOverlay != null) {
            touchOverlay.setMenuMode(inMenu);
            if (inMenu) {
                touchOverlay.setSensorButtonsMask(0);
                touchOverlay.setSteerNormalized(0f);
            }
        }
        if (inMenu) {
            try {
                WearTouchOverlay.nativeSetWearAnalog(true, 128, 128);
            } catch (UnsatisfiedLinkError | Exception ignored) {}
        }
    }

    /**
     * Pulo do kart por Gesto de Braço / Relógio ("poin" subida e descida com o antebraço):
     *
     * O usuário quer um pulo ("poin", subida e descida natural do braço do relógio) que:
     * 1. Funcione com pouco movimento, sem precisar de movimentos bruscos ou trancos fortes que tirem os olhos da tela.
     * 2. Reconheça a assinatura física completa do gesto de pulo: um pico de aceleração para cima seguido
     *    pelo retorno / descida (ou aceleração vertical rápida na direção normal da tela Z).
     * 3. Não confunda com a rotação de volante/curva: esterçar o volante produz rotação intensa em Giroscópio Z e Y,
     *    mas mínima aceleração vertical pura sem descida em Z.
     */
    private void handleTiltJump(float linX, float linY, float linZ) {
        long now = System.currentTimeMillis();
        if (inMenu || (now - menuExitTime) < POST_MENU_SENSOR_GRACE_MS) {
            jumpGesturePhase = 0;
            peakUpAcc = 0f;
            return;
        }
        if (now - lastJumpTime < JUMP_COOLDOWN_MS) {
            jumpGesturePhase = 0;
            peakUpAcc = 0f;
            return;
        }

        // Rotação pura de direção / curva (volante)
        float steerGyro = Math.abs(lastGyroZ) + Math.abs(lastGyroY) * 0.7f;
        // Se estiver fazendo uma curva muito rápida/forte de esterçamento (sem ser gesto de pulo),
        // evitamos disparar pulo acidental
        if (steerGyro > 3.8f && Math.abs(lastGyroX) < 1.2f) {
            jumpGesturePhase = 0;
            peakUpAcc = 0f;
            return;
        }

        // Aceleração vertical na direção de visualização do relógio (eixo Z normal à tela)
        float vertUpAcc = linZ;

        // Timeout da fase de subida: um pulo "poin" leva de 50ms a 380ms entre subir e descer
        if (jumpGesturePhase == 1 && (now - jumpPhaseStartTime > 380)) {
            jumpGesturePhase = 0;
            peakUpAcc = 0f;
        }

        boolean triggerJump = false;

        if (jumpGesturePhase == 0) {
            // Fase 1: Detectar início da subida rápida do antebraço
            // Limiar suave: ~4.5 m/s² (movimento leve de pulo com o braço, sem ser violento)
            if (vertUpAcc >= 4.4f) {
                jumpGesturePhase = 1;
                jumpPhaseStartTime = now;
                peakUpAcc = vertUpAcc;
            }
        } else if (jumpGesturePhase == 1) {
            // Atualiza pico de subida
            if (vertUpAcc > peakUpAcc) {
                peakUpAcc = vertUpAcc;
            }

            // Fase 2: O "poin" - descida / desaceleração após a subida
            // Quando o braço sobe e para/desce, a aceleração linear inverte ou cai bruscamente
            long elapsedSinceUp = now - jumpPhaseStartTime;
            if (elapsedSinceUp >= 40) { // pelo menos 1-2 frames após o início da subida
                if (vertUpAcc <= 0.8f || (peakUpAcc - vertUpAcc >= 3.8f)) {
                    triggerJump = true;
                }
            }

            // Atalho de resposta instantânea para pulo nítido de ~7.5 m/s²:
            // Dispara imediatamente para não haver atraso perceptível no jogo!
            if (vertUpAcc >= 7.8f) {
                triggerJump = true;
            }
        }

        if (triggerJump) {
            lastJumpTime = now;
            jumpGesturePhase = 0;
            peakUpAcc = 0f;
            Log.d(TAG, "Tilt Jump Triggered! linZ=" + linZ + " peak=" + peakUpAcc);
            if (touchOverlay != null) {
                // Em CTR, R1 (0x0800) faz o kart pular ("poin" / hop) e iniciar o Power Slide!
                touchOverlay.pulseNativeButton(WearTouchOverlay.BTN_MASK_R1, 260);
                touchOverlay.buzz(28);
            }
        }
    }

    /**
     * Controle de Direção, Aceleração e D-Pad por Giroscópio e Acelerômetro:
     *
     * Por que antes estava ruim olhando para o relógio com o pulso erguido?
     * 1. Em VehPhysJoystick.c do CTR, o analógico tem uma deadzone interna de 48 unidades (0x30 de 0x7F = 38%!)
     *    e uma curva de duas fases onde os primeiros 50% viram apenas 1/5 da força!
     * 2. Antes também acionávamos BTN_LEFT / BTN_RIGHT em 32% de inclinação, o que em GAMEPAD.c
     *    sobrescrevia o analógico e travava a roda em 100% (curva brusca sem controle).
     * 3. Quando olhamos para o relógio com o pulso erguido (`gy` ~ 3.5 a 5.5 m/s²), virar o pulso como um
     *    volante ou girar o antebraço projeta gravidade em `gx`, além de rotação em `gyroY` e `gyroZ`.
     *
     * Solução implementada:
     * - Fusão de inclinação gravitacional relativa à postura do pulso + amortecimento preditivo do giroscópio (`gyroY` e `gyroZ`).
     * - Compensação exata da deadzone de `0x30` (48) da física do CTR (`computeCtrAnalogByte`), para que
     *   qualquer inclinação intencional do pulso já produza resposta imediata, progressiva e super precisa!
     * - Aceleração combinada: você pode acelerar segurando X na parte inferior da tela OU inclinando o pulso
     *   para frente (ou ambos juntos sem conflito), virando com total precisão!
     */
    private void handleSteeringAndDpad(float gx, float gy, float gz) {
        long now = System.currentTimeMillis();

        // MODO PULSO ERGUIDO (olhando para o relógio no braço, orientação fixa natural):
        // Auto-adaptação suave do centro neutro quando o pulso está próximo do repouso
        float gyroTotal = Math.abs(lastGyroX) + Math.abs(lastGyroY) + Math.abs(lastGyroZ);
        if (gyroTotal < 0.45f && Math.abs(gx) < 1.6f) {
            baseGx = 0.992f * baseGx + 0.008f * gx;
        }
        if (gyroTotal < 0.45f && gy > 1.2f && gy < 6.2f) {
            baseGy = 0.995f * baseGy + 0.005f * gy;
        }

        // Diferença de inclinação lateral em relação ao centro natural do pulso:
        // Quando você olha para o relógio no pulso erguido e gira o pulso p/ direita ou esquerda
        // (tanto inclinando o relógio quanto girando como um mini-volante), gx varia de -3.2 a +3.2 m/s².
        float centeredGx = gx - (baseGx * 0.45f);

        // Fusão com Giroscópio:
        // -lastGyroY: rotação de pronação/supinação do antebraço
        // -lastGyroZ: giro no plano do mostrador do relógio (como volante olhando para a tela)
        float gyroAssist = (-lastGyroY * 0.11f) + (-lastGyroZ * 0.14f);
        gyroAssist = Math.max(-0.35f, Math.min(0.35f, gyroAssist));

        // Sensibilidade calibrada: ~18° de inclinação do pulso (gx ~ 3.1 m/s²) atinge 100% de curva!
        float rawSteer = (-centeredGx / 3.15f) + gyroAssist;

        // Pitch no pulso erguido:
        // Posição natural olhando para o relógio tem baseGy (~3.8 m/s²).
        // Inclinar o pulso para frente (nivelando o relógio, gy diminui) -> rawPitch positivo (Cima / Acelerar)
        // Inclinar o pulso na direção do rosto (gy aumenta) -> rawPitch negativo (Baixo / Ré nos menus)
        float rawPitch = baseGy - gy;

        // Filtro responsivo de baixa latência (60% estado anterior + 40% leitura atual)
        smoothSteerX = 0.60f * smoothSteerX + 0.40f * rawSteer;
        smoothPitchY = 0.68f * smoothPitchY + 0.32f * rawPitch;

        // Deadzone pequena no pulso (5%) apenas para manter o kart reto quando o pulso está parado
        float steerNorm = smoothSteerX;
        final float wristDeadzone = 0.05f;
        if (Math.abs(steerNorm) < wristDeadzone) {
            steerNorm = 0f;
        } else {
            float sign = Math.signum(steerNorm);
            float mag = Math.min(1.0f, (Math.abs(steerNorm) - wristDeadzone) / (1.0f - wristDeadzone));
            // Curva levemente progressiva (expoente 1.15) para controle fino no centro e resposta forte nas curvas
            steerNorm = sign * (float) Math.pow(mag, 1.15);
        }

        // Converte [-1.0, +1.0] para o byte analógico [0..255] pré-compensando a deadzone 0x30 (48) do CTR!
        int lxByte = computeCtrAnalogByte(steerNorm);
        int lyByte = 128;

        // Mapeamento para botões de sensor:
        // IMPORTANTE: NÃO ligamos BTN_MASK_LEFT / BTN_MASK_RIGHT em curvas normais ou médias,
        // pois em GAMEPAD.c do CTR (GAMEPAD_ProcessSticks_ResolveAxis) o D-Pad anula o stick analógico
        // e força a curva para 100% instantaneamente!
        // Como analogButtonsEnabled = 1 no CTR, valores extremos do stick (lxByte < 0x20 ou > 0xE0)
        // já acionam esquerda/direita nos menus automaticamente!
        int sensorMask = 0;

        // Inclinação longitudinal (Frente / Trás):
        // - Nos menus: inclinar p/ frente (smoothPitchY > 2.1) sobe (UP) e p/ trás (< -2.1) desce (DOWN).
        // - Na corrida: se você não estiver tocando no Freio (Quadrado), inclinar o relógio para frente
        //   (smoothPitchY > 2.4) também aciona o acelerador (X), permitindo acelerar no tilt ou no toque!
        boolean brakingOnTouch = (touchOverlay != null) && touchOverlay.isButtonHeldByTouch(WearTouchOverlay.BTN_MASK_SQUARE);

        if (smoothPitchY > 2.1f) {
            sensorMask |= WearTouchOverlay.BTN_MASK_UP;
            lyByte = 16;
            if (!brakingOnTouch && smoothPitchY > 2.45f) {
                sensorMask |= WearTouchOverlay.BTN_MASK_CROSS;
            }
        } else if (smoothPitchY < -2.1f) {
            sensorMask |= WearTouchOverlay.BTN_MASK_DOWN;
            lyByte = 240;
        }

        // Nos menus (e logo após sair deles) o pulso não controla nada: stick centrado, sem botões de sensor.
        if (inMenu || (now - menuExitTime) < POST_MENU_SENSOR_GRACE_MS) {
            sensorMask = 0;
            lxByte = 128;
            lyByte = 128;
            steerNorm = 0f;
        }

        // Sempre mantém o modo analógico ativo (true) para que o CTR use o stickLX contínuo sem saltos
        try {
            WearTouchOverlay.nativeSetWearAnalog(true, lxByte, lyByte);
        } catch (UnsatisfiedLinkError | Exception ignored) {}

        if (touchOverlay != null) {
            touchOverlay.setSensorButtonsMask(sensorMask);
            touchOverlay.setSteerNormalized(steerNorm);
        }
    }

    /**
     * Pré-compensa a curva interna de `VehPhysJoystick_GetStrength` (game/Vehicle/VehPhysJoystick.c):
     * No CTR original:
     * - Centro = 128 (0x80)
     * - Deadzone = 48 (0x30): valores entre 80 e 176 produzem ZERO curva no jogo!
     * - O primeiro segmento após 48 entrega apenas 20% da força de curva, e o segundo entrega os 80% restantes.
     *
     * Esta função mapeia nosso `steerNorm` [-1.0 .. +1.0] diretamente para a zona efetiva [49 .. 127] de
     * distância do centro, garantindo que assim que você gira o pulso além de `wristDeadzone`, o kart
     * já comece a virar suavemente e de forma linear com o movimento do seu pulso!
     */
    private int computeCtrAnalogByte(float steerNorm) {
        if (Math.abs(steerNorm) < 0.001f) {
            return 128;
        }
        float sign = Math.signum(steerNorm);
        float mag = Math.min(1.0f, Math.abs(steerNorm));

        // Salta imediatamente para a borda da deadzone do CTR (48) e distribui de 49 até 127:
        // Usa duas inclinações para linearizar a curva de 2 estágios do CTR:
        float ctrOffset;
        if (mag <= 0.35f) {
            // Micro-ajustes de direção na reta (mapeia 0..0.35 para 49..88 de offset)
            float t = mag / 0.35f;
            ctrOffset = 49f + t * 39f;
        } else {
            // Curvas médias a fechadas (mapeia 0.35..1.0 para 88..127 de offset)
            float t = (mag - 0.35f) / 0.65f;
            ctrOffset = 88f + t * 39f;
        }

        int byteVal = Math.round(128f + sign * ctrOffset);
        return Math.max(0, Math.min(255, byteVal));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (touchOverlay != null) {
                    touchOverlay.pulseNativeButton(WearTouchOverlay.BTN_MASK_START, 220);
                    touchOverlay.showCenterBanner("START", WearTouchOverlay.COLOR_TRIANGLE, 900);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        // Relógio redondo não deve alterar rotação da janela SDL
    }

    @Override
    public void setRequestedOrientation(int requestedOrientation) {
        // Mantém fixo na tela do relógio
    }

    /**
     * Gira a superfície de renderização do jogo (SDLSurface) para acompanhar a orientação física do relógio:
     * - Modo Pulso (0): 0° (vertical normal)
     * - Modo Volante Topo à Esquerda (1): 270° (-90° anti-horário) para que o topo do jogo fique em cima
     * - Modo Volante Topo à Direita (-1): 90° (+90° horário) para que o topo do jogo fique em cima
     */
    private void applyDisplayRotationForOrientation(final int orientation) {
        final float targetDegrees;
        if (orientation == 1) {
            targetDegrees = 270f;
        } else if (orientation == -1) {
            targetDegrees = 90f;
        } else {
            targetDegrees = 0f;
        }

        runOnUiThread(() -> {
            View surface = SDLActivity.mSurface;
            if (surface != null) {
                float w = surface.getWidth();
                float h = surface.getHeight();
                if (w > 0 && h > 0) {
                    surface.setPivotX(w * 0.5f);
                    surface.setPivotY(h * 0.5f);
                }
                surface.setRotation(targetDegrees);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyDisplayRotationForOrientation(wheelOrientation);
        if (sensorManager != null) {
            if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            if (gyroscope != null) sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    protected void onDestroy() {
        if (romInbox != null) {
            romInbox.stop();
        }
        super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
