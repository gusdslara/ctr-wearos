package com.ctrnative;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Tela circular apresentada no relógio caso a ROM ou smw_assets.dat ainda não estejam presentes.
 * Mostra o QR Code e o endereço IP/Porta para o usuário transferir diretamente do celular ou PC.
 */
public class RomTransferView extends LinearLayout {

    private final ImageView qrImageView;
    private final TextView titleView;
    private final TextView urlView;
    private final TextView statusView;
    private final ProgressBar progressBar;

    public RomTransferView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(Color.BLACK);
        int pad = (int) (context.getResources().getDisplayMetrics().density * 16);
        setPadding(pad, pad, pad, pad);

        titleView = new TextView(context);
        titleView.setText("CRASH TEAM RACING");
        titleView.setTextColor(Color.rgb(255, 68, 68));
        titleView.setTextSize(13f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        addView(titleView);

        qrImageView = new ImageView(context);
        int qrPx = (int) (context.getResources().getDisplayMetrics().density * 130);
        LayoutParams imgLp = new LayoutParams(qrPx, qrPx);
        imgLp.topMargin = (int) (context.getResources().getDisplayMetrics().density * 6);
        imgLp.bottomMargin = (int) (context.getResources().getDisplayMetrics().density * 6);
        qrImageView.setLayoutParams(imgLp);
        addView(qrImageView);

        urlView = new TextView(context);
        urlView.setText("Conectando ao Wi-Fi...");
        urlView.setTextColor(Color.WHITE);
        urlView.setTextSize(11f);
        urlView.setTypeface(Typeface.MONOSPACE);
        urlView.setGravity(Gravity.CENTER);
        addView(urlView);

        statusView = new TextView(context);
        statusView.setText("Escaneie com o celular para enviar a ROM");
        statusView.setTextColor(Color.rgb(180, 180, 180));
        statusView.setTextSize(10f);
        statusView.setGravity(Gravity.CENTER);
        LayoutParams statusLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = (int) (context.getResources().getDisplayMetrics().density * 4);
        statusView.setLayoutParams(statusLp);
        addView(statusView);

        progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        LayoutParams barLp = new LayoutParams(LayoutParams.MATCH_PARENT, (int) (context.getResources().getDisplayMetrics().density * 8));
        barLp.topMargin = (int) (context.getResources().getDisplayMetrics().density * 8);
        barLp.leftMargin = (int) (context.getResources().getDisplayMetrics().density * 24);
        barLp.rightMargin = (int) (context.getResources().getDisplayMetrics().density * 24);
        progressBar.setLayoutParams(barLp);
        addView(progressBar);
    }

    public void setQrCode(String url, Bitmap qr) {
        urlView.setText(url);
        if (qr != null) {
            qrImageView.setImageBitmap(qr);
            qrImageView.setVisibility(View.VISIBLE);
        }
    }

    public void setProgress(int percent) {
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(percent);
        statusView.setText("Enviando ROM: " + percent + "%");
    }

    public void setStatus(String text, int color) {
        statusView.setText(text);
        statusView.setTextColor(color);
    }
}
