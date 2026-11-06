package com.ctrnative;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * Overlay de controles minimalista para Crash Team Racing no Wear OS:
 *
 * 1. Design Minimalista (idêntico a smw-wearos e gen1recomp):
 *    - A tela fica 100% limpa durante o jogo (nenhum botão estático desenhado).
 *    - Ao ativar um botão, surge apenas um arco luminoso colorido na borda circular
 *      junto ao símbolo Sony correspondente (△, ✕, □, ○, L1, R1, L2, R2) com fade-out suave (280ms).
 *    - Ao virar o relógio para dirigir, um arco sutil na borda esquerda/direita mostra a intensidade
 *      da curva analógica em tempo real.
 *
 * 2. Layout de Toque (Sem D-Pad na tela — o giro do relógio controla a direção/D-Pad):
 *    - Botões Sony ocupam a tela inteira nas mesmas 4 posições cardinais de uma cruz,
 *      mas quando o jogador já está segurando X (acelerando embaixo), o dedo fica "travado" no X
 *      (sticky hold) enquanto estiver na metade inferior da tela para que inclinar o pulso e girar
 *      nunca faça o polegar escorregar para Quadrado ou Bola por acidente!
 *      * Cima (Top):      Triângulo (△) - Verde Sony
 *      * Baixo (Bottom):  X (✕)         - Azul Sony (zona ampla de 110° na parte inferior para conforto ao acelerar)
 *      * Esquerda (Left): Quadrado (□)  - Rosa Sony
 *      * Direita (Right): Círculo (○)   - Vermelho Sony
 *    - 4 Cantos Diagonais da Borda:
 *      * Superior Esquerdo: L1
 *      * Superior Direito:  R1
 *      * Inferior Esquerdo: L2
 *      * Inferior Direito:  R2
 *
 * 2b. Modo Menu (menus, pausa, resultados, loading):
 *    - D-Pad invisível no centro da tela (disco interno ~55% do raio): toque Cima/Baixo/Esq/Dir
 *      para navegar. Só aparece uma setinha quando tocado.
 *    - O anel externo continua com ✕ (confirmar), △ (voltar), □, ○ e L1/R1/L2/R2.
 *    - Inclinação/giro do pulso e pulo por tilt ficam desligados (ver WearCTRActivity).
 *
 * 3. Gestos Multi-Toque com 2 Dedos:
 *    - 2 toques com 2 dedos = SELECT (exibe "SELECT" no meio da tela e some suavemente).
 *    - 3 toques com 2 dedos = START  (exibe "START" no meio da tela e some suavemente).
 */
public class WearTouchOverlay extends View {

    // JNI Native Input & Renderer methods
    public static native void nativeSetTouchButtons(int buttons);
    public static native void nativeSetWearAnalog(boolean active, int lx, int ly);
    public static native void nativeSetWearZoomMode(int mode);
    public static native boolean nativeIsInMenu();

    // Raio (fração de r) do D-Pad invisível central usado nos menus
    private static final float MENU_DPAD_RADIUS = 0.55f;
    private static final float MENU_DPAD_CENTER_DEADZONE = 0.08f;
    public static final int COLOR_DPAD = Color.rgb(235, 240, 250);
    private static final int DPAD_MASKS = 0x00F0; // UP | RIGHT | DOWN | LEFT

    // PlayStation bitmasks em native_input.c (Active LOW: 0 = pressionado, 1 = liberado)
    public static final int BTN_MASK_SELECT   = 0x0001; // RAW_BTN_SELECT = 1
    public static final int BTN_MASK_L3       = 0x0002;
    public static final int BTN_MASK_R3       = 0x0004;
    public static final int BTN_MASK_START    = 0x0008; // RAW_BTN_START = 8
    public static final int BTN_MASK_UP       = 0x0010; // RAW_BTN_UP = 0x10
    public static final int BTN_MASK_RIGHT    = 0x0020; // RAW_BTN_RIGHT = 0x20
    public static final int BTN_MASK_DOWN     = 0x0040; // RAW_BTN_DOWN = 0x40
    public static final int BTN_MASK_LEFT     = 0x0080; // RAW_BTN_LEFT = 0x80
    public static final int BTN_MASK_L2       = 0x0100; // RAW_BTN_L2 = 0x100
    public static final int BTN_MASK_R2       = 0x0200; // RAW_BTN_R2 = 0x200
    public static final int BTN_MASK_L1       = 0x0400; // RAW_BTN_L1 = 0x400
    public static final int BTN_MASK_R1       = 0x0800; // RAW_BTN_R1 = 0x800
    public static final int BTN_MASK_TRIANGLE = 0x1000; // RAW_BTN_TRIANGLE = 0x1000
    public static final int BTN_MASK_CIRCLE   = 0x2000; // RAW_BTN_CIRCLE = 0x2000
    public static final int BTN_MASK_CROSS    = 0x4000; // RAW_BTN_CROSS = 0x4000
    public static final int BTN_MASK_SQUARE   = 0x8000; // RAW_BTN_SQUARE = 0x8000

    // Cores PlayStation autênticas
    public static final int COLOR_CROSS    = Color.rgb(91, 134, 229);  // Azul PlayStation (✕)
    public static final int COLOR_SQUARE   = Color.rgb(224, 86, 253);  // Rosa/Magenta PlayStation (□)
    public static final int COLOR_CIRCLE   = Color.rgb(255, 71, 87);   // Vermelho/Coral PlayStation (○)
    public static final int COLOR_TRIANGLE = Color.rgb(46, 213, 115);  // Verde PlayStation (△)
    public static final int COLOR_SHOULDER = Color.rgb(255, 195, 75);  // Âmbar / Ouro (L1/R1/L2/R2)
    public static final int COLOR_STEER    = Color.rgb(160, 215, 255); // Azul-gelo sutil para feedback de direção

    private static final long FLASH_DURATION_MS = 280;

    // Estado dos botões combinados (Toque + Sensores + Pulsos temporários)
    private int touchButtonsMask = 0;
    private int sensorButtonsMask = 0;
    private int pulseButtonsMask = 0;

    // Intensidade normalizada da direção [-1.0 .. +1.0] para feedback minimalista de curva na borda
    private float steerNormalized = 0f;

    // Guarda contra zoom acidental da coroa/bezel durante toque na tela
    private long lastTouchEndTime = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // Feedback no meio da tela ("START" / "SELECT" / Zoom)
    private String centerBannerText = null;
    private long centerBannerEndTime = 0;
    private int centerBannerColor = Color.WHITE;

    // Arcos minimalistas de ativação na borda circular (como smw-wearos / gen1recomp)
    private static class RimFlash {
        int mask;
        String label;
        float angleDeg; // 0 = Direita, 90 = Baixo, 180 = Esquerda, 270 = Cima
        int color;
        long startTime;
    }

    private final Map<Integer, RimFlash> activeFlashes = new HashMap<>();
    private final Map<Integer, Integer> pointerToMask = new HashMap<>();

    // Orientação do relógio:
    // Orientação: sempre no modo pulso erguido (0)
    private static final int wheelOrientation = 0;

    // true quando o jogo está em menu/pausa/loading: ativa o D-Pad central invisível
    private boolean menuMode = true;

    // Pinturas reutilizáveis
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint symbolPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bannerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bannerBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ovalRect = new RectF();
    private final RectF bannerRect = new RectF();
    private final Path trianglePath = new Path();

    private final Vibrator vibrator;
    private final Context hostContext;

    public WearTouchOverlay(Context context) {
        super(context);
        this.hostContext = context;
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setColor(Color.WHITE);

        symbolPaint.setStyle(Paint.Style.STROKE);
        symbolPaint.setStrokeWidth(3.8f);
        symbolPaint.setStrokeCap(Paint.Cap.ROUND);
        symbolPaint.setStrokeJoin(Paint.Join.ROUND);

        bannerPaint.setTextAlign(Paint.Align.CENTER);
        bannerPaint.setFakeBoldText(true);

        bannerBgPaint.setStyle(Paint.Style.FILL);
        bannerBgPaint.setColor(Color.argb(215, 14, 16, 22));
    }

    public boolean isTouchGuarded(long guardDurationMs) {
        if (!pointerToMask.isEmpty() || touchButtonsMask != 0) {
            return true;
        }
        return (System.currentTimeMillis() - lastTouchEndTime) < guardDurationMs;
    }

    public boolean hasActiveTouch() {
        return !pointerToMask.isEmpty() || touchButtonsMask != 0;
    }

    public boolean isButtonHeldByTouch(int mask) {
        return (touchButtonsMask & mask) != 0;
    }

    /**
     * Atualiza a intensidade normalizada da curva [-1.0 .. +1.0] para desenhar um arco sutil na borda
     * quando o jogador vira o relógio.
     */
    public void setSteerNormalized(float steer) {
        if (Math.abs(this.steerNormalized - steer) > 0.03f) {
            this.steerNormalized = steer;
            postInvalidateOnAnimation();
        }
    }

    /**
     * Define a orientação caso o usuário tire o relógio do pulso e segure pelas pulseiras (Modo Wii Wheel).
     * @param orientation 0 = Pulso normal, 1 = Pulseira horizontal (topo à esquerda), -1 = Pulseira horizontal (topo à direita)
     */
    public void setWheelOrientation(int orientation) {
        // Modo volante / virar botões removido: orientado sempre fixo para a visão do pulso
    }

    public void setMenuMode(boolean inMenu) {
        if (this.menuMode != inMenu) {
            this.menuMode = inMenu;
            if (inMenu) {
                steerNormalized = 0f;
            }
            postInvalidate();
        }
    }

    public boolean isMenuMode() {
        return menuMode;
    }

    public int getWheelOrientation() {
        return wheelOrientation;
    }

    public void buzz(long millis) {
        if (vibrator != null) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(millis);
                }
            } catch (Exception ignored) {}
        }
    }

    public void showCenterBanner(String text, int color, long durationMs) {
        centerBannerText = text;
        centerBannerColor = color;
        centerBannerEndTime = System.currentTimeMillis() + durationMs;
        buzz(30);
        postInvalidate();
    }

    private void syncNativeButtons() {
        int combined = touchButtonsMask | sensorButtonsMask | pulseButtonsMask;
        // Native expects active-low bitmask (1 = released, 0 = pressed)
        int nativeMask = (~combined) & 0xFFFF;
        try {
            nativeSetTouchButtons(nativeMask);
        } catch (UnsatisfiedLinkError | Exception ignored) {}
    }

    /**
     * Atualiza botões controlados continuamente pelo giroscópio/acelerômetro (D-Pad nos menus / auto-throttle).
     */
    public void setSensorButtonsMask(int mask) {
        if (this.sensorButtonsMask != mask) {
            this.sensorButtonsMask = mask;
            syncNativeButtons();
            postInvalidate();
        }
    }

    /**
     * Pulsa um botão nativo por durationMs (ex: Pulo com R1, Start, Select) e acende o arco minimalista na borda.
     */
    public void pulseNativeButton(final int mask, long durationMs) {
        pulseButtonsMask |= mask;
        syncNativeButtons();
        triggerRimFlash(mask);
        handler.postDelayed(() -> {
            pulseButtonsMask &= ~mask;
            syncNativeButtons();
            postInvalidate();
        }, durationMs);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerIndex = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIndex);
        int pointerCount = event.getPointerCount();
        long now = System.currentTimeMillis();

        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return true;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                lastTouchEndTime = now;
                float x = event.getX(pointerIndex);
                float y = event.getY(pointerIndex);
                int mask = resolveButtonMask(x, y, w, h, 0);
                if (mask != 0) {
                    pointerToMask.put(pointerId, mask);
                    rebuildTouchMask();
                    triggerRimFlash(mask);
                    buzz(14);
                    if (mask == BTN_MASK_CROSS && hostContext instanceof WearCTRActivity) {
                        ((WearCTRActivity) hostContext).notifyThrottleEngaged();
                    }
                }
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                lastTouchEndTime = now;
                boolean changed = false;
                for (int i = 0; i < pointerCount; i++) {
                    int pId = event.getPointerId(i);
                    float px = event.getX(i);
                    float py = event.getY(i);
                    Integer oldObj = pointerToMask.get(pId);
                    int oldMask = (oldObj != null) ? oldObj : 0;
                    int newMask = resolveButtonMask(px, py, w, h, oldMask);
                    if (oldMask != newMask) {
                        changed = true;
                        if (newMask != 0) {
                            pointerToMask.put(pId, newMask);
                            triggerRimFlash(newMask);
                            buzz(12);
                        } else {
                            pointerToMask.remove(pId);
                        }
                    }
                }
                if (changed) {
                    rebuildTouchMask();
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                pointerToMask.remove(pointerId);
                lastTouchEndTime = now;
                rebuildTouchMask();
                break;
            }

            case MotionEvent.ACTION_CANCEL: {
                pointerToMask.clear();
                lastTouchEndTime = now;
                rebuildTouchMask();
                break;
            }
        }

        return true;
    }

    private void rebuildTouchMask() {
        int m = 0;
        for (int btn : pointerToMask.values()) {
            m |= btn;
        }
        touchButtonsMask = m;
        syncNativeButtons();
        postInvalidate();
    }

    /**
     * Mapeia o toque (x, y) na tela redonda para os botões Sony:
     * - Sem D-Pad na tela!
     * - Botões principais nas posições da cruz:
     *   * Cima:     Triângulo (△)
     *   * Baixo:    X (✕) -> Zona ampla na parte inferior + histerese ("Sticky X") enquanto segurado,
     *                        permitindo girar o pulso livremente enquanto acelera sem perder o X!
     *   * Esquerda: Quadrado (□)
     *   * Direita:  Círculo (○)
     * - 4 Cantos Diagonais na borda externa (distância > 0.72 * R e ângulo nas diagonais 45°/135°/225°/315°):
     *   * Superior Esquerdo: L1
     *   * Superior Direito:  R1
     *   * Inferior Esquerdo: L2
     *   * Inferior Direito:  R2
     */
    private int resolveButtonMask(float x, float y, float w, float h, int currentHeldMask) {
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float r = Math.min(w, h) * 0.5f;

        float dx = x - cx;
        float dy = y - cy;

        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        // Angle em graus: 0° = Direita, 90° = Baixo, 180° = Esquerda, 270° = Cima
        float angleDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
        if (angleDeg < 0) angleDeg += 360f;

        // MODO MENU: D-Pad invisível no centro. Quadrantes de 90° em torno das 4 direções.
        if (menuMode && dist < r * MENU_DPAD_RADIUS) {
            if (dist < r * MENU_DPAD_CENTER_DEADZONE) {
                // Bem no centro: mantém a direção atual se já estiver segurando uma, senão nada
                return ((currentHeldMask & DPAD_MASKS) != 0) ? currentHeldMask : 0;
            }
            if (angleDeg >= 45f && angleDeg < 135f) return BTN_MASK_DOWN;
            if (angleDeg >= 135f && angleDeg < 225f) return BTN_MASK_LEFT;
            if (angleDeg >= 225f && angleDeg < 315f) return BTN_MASK_UP;
            return BTN_MASK_RIGHT;
        }

        // Histerese "Sticky X" para Aceleração + Direção:
        // Quando o jogador já está segurando X (acelerando) e gira o pulso para fazer curvas
        // ou usa outro dedo para Bola/Poder, mantemos o X firme enquanto o dedo estiver na metade inferior!
        if (!menuMode && currentHeldMask == BTN_MASK_CROSS) {
            if (dy > -r * 0.05f && angleDeg >= 15f && angleDeg <= 165f) {
                return BTN_MASK_CROSS;
            }
        }

        // Histerese "Sticky Circle" (Bola / Disparar Poder):
        // Se o jogador estiver segurando o Círculo (lado direito) para usar um poder enquanto acelera com X,
        // mantemos o Círculo firme mesmo com leve variação do dedo.
        if (!menuMode && currentHeldMask == BTN_MASK_CIRCLE) {
            if (dx > -r * 0.05f && (angleDeg <= 65f || angleDeg >= 295f)) {
                return BTN_MASK_CIRCLE;
            }
        }

        // 1. Verifica os cantos diagonais na borda extrema (L1, R1, L2, R2)
        // Somente na borda bem externa (dist > 0.82 * r) e bem nas diagonais,
        // garantindo que tocar no Círculo (○) ou X (✕) NUNCA seja capturado por R2 por engano!
        if (dist > r * 0.82f) {
            if (angleDeg >= 210f && angleDeg <= 240f) {
                return BTN_MASK_L1; // Superior Esquerdo
            }
            if (angleDeg >= 300f && angleDeg <= 330f) {
                return BTN_MASK_R1; // Superior Direito
            }
            if (angleDeg >= 125f && angleDeg <= 148f) {
                return BTN_MASK_L2; // Inferior Esquerdo
            }
            if (angleDeg >= 32f && angleDeg <= 52f) {
                return BTN_MASK_R2; // Inferior Direito
            }
        }

        // 2. Cruz principal:
        // Cima = Triângulo, Baixo = X, Esquerda = Quadrado, Direita = Círculo
        // Zona do X: metade inferior generosa (dy > 0 e ângulo entre 35° e 145°)
        if (dy > 0 && angleDeg >= 35f && angleDeg <= 145f) {
            return BTN_MASK_CROSS;
        }
        if (dy < 0 && angleDeg >= 220f && angleDeg <= 320f) {
            return BTN_MASK_TRIANGLE;
        }
        return (dx < 0) ? BTN_MASK_SQUARE : BTN_MASK_CIRCLE;
    }

    private float getRimAngleForOrientation(int mask) {
        // Orientação fixa no modo pulso normal (0)
        if ((mask & (BTN_MASK_TRIANGLE | BTN_MASK_UP)) != 0) return 270f;
        if ((mask & (BTN_MASK_CROSS | BTN_MASK_DOWN)) != 0) return 90f;
        if ((mask & (BTN_MASK_SQUARE | BTN_MASK_LEFT)) != 0) return 180f;
        if ((mask & (BTN_MASK_CIRCLE | BTN_MASK_RIGHT)) != 0) return 0f;
        if ((mask & BTN_MASK_L1) != 0) return 225f;
        if ((mask & BTN_MASK_R1) != 0) return 315f;
        if ((mask & BTN_MASK_L2) != 0) return 135f;
        if ((mask & BTN_MASK_R2) != 0) return 45f;
        return 0f;
    }

    public void triggerRimFlash(int mask) {
        RimFlash flash = new RimFlash();
        flash.mask = mask;
        flash.startTime = System.currentTimeMillis();
        flash.angleDeg = getRimAngleForOrientation(mask);

        if ((mask & BTN_MASK_TRIANGLE) != 0) {
            flash.label = "△";
            flash.color = COLOR_TRIANGLE;
        } else if ((mask & BTN_MASK_CROSS) != 0) {
            flash.label = "✕";
            flash.color = COLOR_CROSS;
        } else if ((mask & BTN_MASK_SQUARE) != 0) {
            flash.label = "□";
            flash.color = COLOR_SQUARE;
        } else if ((mask & BTN_MASK_CIRCLE) != 0) {
            flash.label = "○";
            flash.color = COLOR_CIRCLE;
        } else if ((mask & BTN_MASK_L1) != 0) {
            flash.label = "L1";
            flash.color = COLOR_SHOULDER;
        } else if ((mask & BTN_MASK_R1) != 0) {
            flash.label = "R1";
            flash.color = COLOR_SHOULDER;
        } else if ((mask & BTN_MASK_L2) != 0) {
            flash.label = "L2";
            flash.color = COLOR_SHOULDER;
        } else if ((mask & BTN_MASK_R2) != 0) {
            flash.label = "R2";
            flash.color = COLOR_SHOULDER;
        } else if ((mask & BTN_MASK_UP) != 0) {
            flash.label = "^";
            flash.color = COLOR_DPAD;
        } else if ((mask & BTN_MASK_DOWN) != 0) {
            flash.label = "v";
            flash.color = COLOR_DPAD;
        } else if ((mask & BTN_MASK_LEFT) != 0) {
            flash.label = "<";
            flash.color = COLOR_DPAD;
        } else if ((mask & BTN_MASK_RIGHT) != 0) {
            flash.label = ">";
            flash.color = COLOR_DPAD;
        } else {
            return;
        }

        activeFlashes.put(mask, flash);
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float r = Math.min(cx, cy);
        float lw = Math.max(4.5f, r * 0.038f);
        arcPaint.setStrokeWidth(lw);

        long now = System.currentTimeMillis();
        boolean needsMoreFrames = false;

        // Apenas toques acendem o D-Pad (o sensor nunca envia direções nos menus)
        int heldMask = touchButtonsMask | pulseButtonsMask | (sensorButtonsMask & BTN_MASK_CROSS);

        // 0. Indicador minimalista de direção na borda (só aparece quando o relógio está inclinado virando)
        if (!menuMode && Math.abs(steerNormalized) > 0.08f) {
            float mag = Math.min(1.0f, Math.abs(steerNormalized));
            float steerAngle = (steerNormalized < 0) ? 180f : 0f;
            float span = 12f + mag * 34f;
            int steerAlpha = (int) (45 + mag * 125);

            arcPaint.setStrokeWidth(lw * 0.72f);
            arcPaint.setColor(COLOR_STEER);
            arcPaint.setAlpha(steerAlpha);
            float pad = lw * 1.1f;
            ovalRect.set(cx - r + pad, cy - r + pad, cx + r - pad, cy + r - pad);
            canvas.drawArc(ovalRect, steerAngle - span * 0.5f, span, false, arcPaint);
        }

        // 1. Mantém acesos os arcos dos botões que estão sendo segurados no momento
        if (heldMask != 0) {
            int[] checkMasks = {
                BTN_MASK_TRIANGLE, BTN_MASK_CROSS, BTN_MASK_SQUARE, BTN_MASK_CIRCLE,
                BTN_MASK_L1, BTN_MASK_R1, BTN_MASK_L2, BTN_MASK_R2,
                BTN_MASK_UP, BTN_MASK_DOWN, BTN_MASK_LEFT, BTN_MASK_RIGHT
            };
            for (int m : checkMasks) {
                if ((heldMask & m) != 0) {
                    RimFlash rf = activeFlashes.get(m);
                    if (rf == null) {
                        triggerRimFlash(m);
                        rf = activeFlashes.get(m);
                    }
                    if (rf != null) {
                        rf.startTime = now; // Mantém brilho máximo enquanto segurado
                    }
                }
            }
        }

        // 2. Desenha apenas os arcos minimalistas ativos na borda (com fade-out de 280ms quando solto)
        for (RimFlash rf : activeFlashes.values()) {
            boolean isHeld = (heldMask & rf.mask) != 0;
            long elapsed = isHeld ? 0 : (now - rf.startTime);
            if (elapsed < FLASH_DURATION_MS) {
                if (!isHeld) {
                    needsMoreFrames = true;
                }
                float progress = 1.0f - ((float) elapsed / FLASH_DURATION_MS);
                int alpha = (int) (progress * 235);

                drawRimIndicator(canvas, cx, cy, r, lw, rf, alpha);
            }
        }

        // 3. Desenha o banner flutuante no meio da tela ("SELECT" / "START" / Modo) e some suavemente
        if (centerBannerText != null && now < centerBannerEndTime) {
            needsMoreFrames = true;
            float remain = centerBannerEndTime - now;
            int alpha = (remain < 280f) ? (int) (255 * (remain / 280f)) : 255;

            bannerBgPaint.setAlpha((int) (alpha * 0.88f));
            float bw = r * 0.96f;
            float bh = 40f;
            bannerRect.set(cx - bw * 0.5f, cy - bh * 0.5f, cx + bw * 0.5f, cy + bh * 0.5f);
            canvas.drawRoundRect(bannerRect, 20f, 20f, bannerBgPaint);

            arcPaint.setStrokeWidth(2.5f);
            arcPaint.setColor(centerBannerColor);
            arcPaint.setAlpha(alpha);
            canvas.drawRoundRect(bannerRect, 20f, 20f, arcPaint);

            bannerPaint.setColor(Color.WHITE);
            bannerPaint.setAlpha(alpha);
            bannerPaint.setTextSize(20f);
            Paint.FontMetrics fm = bannerPaint.getFontMetrics();
            float textY = cy - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(centerBannerText, cx, textY, bannerPaint);
        } else {
            centerBannerText = null;
        }

        if (needsMoreFrames) {
            postInvalidateOnAnimation();
        }
    }

    /**
     * Setinha (chevron) do D-Pad invisível dos menus, desenhada dentro do disco central só enquanto tocada.
     */
    private void drawDpadChevron(Canvas canvas, float cx, float cy, float r, RimFlash rf, int alpha) {
        double rad = Math.toRadians(rf.angleDeg);
        float ux = (float) Math.cos(rad);
        float uy = (float) Math.sin(rad);
        // Vetor perpendicular
        float px = -uy;
        float py = ux;

        float tipDist = r * MENU_DPAD_RADIUS * 0.78f;
        float size = r * 0.13f;
        float tipX = cx + ux * tipDist;
        float tipY = cy + uy * tipDist;
        float baseX = cx + ux * (tipDist - size);
        float baseY = cy + uy * (tipDist - size);

        symbolPaint.setColor(rf.color);
        symbolPaint.setAlpha(alpha);
        float oldWidth = symbolPaint.getStrokeWidth();
        symbolPaint.setStrokeWidth(Math.max(4f, r * 0.03f));
        trianglePath.reset();
        trianglePath.moveTo(baseX + px * size, baseY + py * size);
        trianglePath.lineTo(tipX, tipY);
        trianglePath.lineTo(baseX - px * size, baseY - py * size);
        canvas.drawPath(trianglePath, symbolPaint);
        symbolPaint.setStrokeWidth(oldWidth);
    }

    /**
     * Desenha um arco colorido na borda circular e o símbolo Sony vetorial (△, ✕, □, ○) ou label (L1, R1, L2, R2) logo abaixo da borda.
     */
    private void drawRimIndicator(Canvas canvas, float cx, float cy, float r, float lw, RimFlash rf, int alpha) {
        if ((rf.mask & DPAD_MASKS) != 0) {
            drawDpadChevron(canvas, cx, cy, r, rf, alpha);
            return;
        }
        arcPaint.setStrokeWidth(lw);
        arcPaint.setColor(rf.color);
        arcPaint.setAlpha(alpha);

        float pad = lw * 1.1f;
        ovalRect.set(cx - r + pad, cy - r + pad, cx + r - pad, cy + r - pad);
        float span = (rf.label.length() > 1) ? 34f : 42f;
        canvas.drawArc(ovalRect, rf.angleDeg - span * 0.5f, span, false, arcPaint);

        double rad = Math.toRadians(rf.angleDeg);
        float iconDist = r - lw * 3.6f;
        float ix = cx + (float) Math.cos(rad) * iconDist;
        float iy = cy + (float) Math.sin(rad) * iconDist;

        float symRadius = 9.5f;
        symbolPaint.setColor(rf.color);
        symbolPaint.setAlpha(alpha);

        if ((rf.mask & BTN_MASK_TRIANGLE) != 0) {
            trianglePath.reset();
            trianglePath.moveTo(ix, iy - symRadius);
            trianglePath.lineTo(ix + symRadius * 0.95f, iy + symRadius * 0.75f);
            trianglePath.lineTo(ix - symRadius * 0.95f, iy + symRadius * 0.75f);
            trianglePath.close();
            canvas.drawPath(trianglePath, symbolPaint);
        } else if ((rf.mask & BTN_MASK_CROSS) != 0) {
            float s = symRadius * 0.78f;
            canvas.drawLine(ix - s, iy - s, ix + s, iy + s, symbolPaint);
            canvas.drawLine(ix + s, iy - s, ix - s, iy + s, symbolPaint);
        } else if ((rf.mask & BTN_MASK_SQUARE) != 0) {
            float s = symRadius * 0.78f;
            canvas.drawRect(ix - s, iy - s, ix + s, iy + s, symbolPaint);
        } else if ((rf.mask & BTN_MASK_CIRCLE) != 0) {
            canvas.drawCircle(ix, iy, symRadius * 0.85f, symbolPaint);
        } else {
            textPaint.setColor(rf.color);
            textPaint.setAlpha(alpha);
            textPaint.setTextSize(16f);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(rf.label, ix, iy - (fm.ascent + fm.descent) * 0.5f, textPaint);
        }
    }
}
