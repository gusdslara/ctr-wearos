package com.ctrnative;

import java.util.HashMap;
import java.util.Map;

/**
 * Página web servida pelo RomInbox para smartphones ou PCs conectados ao mesmo Wi-Fi do relógio.
 */
final class Page {

    private Page() {}

    private static final Map<String, Map<String, String>> T = new HashMap<>();

    private static void lang(String code, String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        T.put(code, m);
    }

    static {
        lang("pt",
                "title", "Enviar ROM para o Super Mario World",
                "hint", "Envie o arquivo smw_assets.dat ou a ROM de Super Mario World (SNES / .sfc / .smc unheadered).",
                "pick", "Escolher arquivo",
                "sending", "Enviando",
                "notNeeded", "O relógio já tem os arquivos do jogo.",
                "notRom", "Arquivo inválido ou corrompido.",
                "okGame", "Arquivo recebido com sucesso! Abrindo o jogo...",
                "cut", "O envio foi interrompido. Tente novamente.",
                "noSpace", "Espaço insuficiente no relógio.");
        lang("en",
                "title", "Send ROM to Super Mario World",
                "hint", "Send smw_assets.dat or the Super Mario World SNES ROM (.sfc / .smc unheadered).",
                "pick", "Choose file",
                "sending", "Sending",
                "notNeeded", "Watch already has game assets.",
                "notRom", "Invalid file.",
                "okGame", "File received! Starting game...",
                "cut", "Upload interrupted. Try again.",
                "noSpace", "Not enough storage on watch.");
    }

    static String t(String lang, String key) {
        Map<String, String> m = T.get(lang);
        String v = m != null ? m.get(key) : null;
        if (v == null) {
            Map<String, String> en = T.get("en");
            v = (en != null) ? en.get(key) : null;
        }
        return v != null ? v : key;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String html(String lang) {
        return "<!doctype html><html lang=\"" + esc(lang) + "\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(t(lang, "title")) + "</title><style>"
                + "body{margin:0;font-family:system-ui,-apple-system,sans-serif;background:#111;color:#eee;display:flex;justify-content:center}"
                + "main{max-width:440px;width:100%;padding:28px 18px;box-sizing:border-box}"
                + "h1{font-size:22px;margin:0 0 12px;color:#ff4444}p{line-height:1.45;color:#bbb;font-size:15px}"
                + "label.btn{display:block;text-align:center;background:#e52521;color:#fff;font-weight:600;"
                + "padding:16px;border-radius:12px;margin:24px 0;font-size:17px;cursor:pointer;box-shadow:0 4px 14px rgba(229,37,33,0.4)}"
                + "input[type=file]{display:none}"
                + ".bar{height:12px;background:#333;border-radius:6px;overflow:hidden;margin-top:18px;display:none}"
                + ".bar div{height:100%;width:0;background:#44bd32;transition:width 0.2s}#msg{margin-top:14px;font-size:16px;min-height:1.4em}"
                + ".ok{color:#4cd137}.err{color:#e84118}</style></head><body><main>"
                + "<h1>" + esc(t(lang, "title")) + "</h1>"
                + "<p>" + esc(t(lang, "hint")) + "</p>"
                + "<label class=\"btn\">" + esc(t(lang, "pick")) + "<input id=\"f\" type=\"file\"></label>"
                + "<div class=\"bar\" id=\"b\"><div id=\"p\"></div></div><div id=\"msg\"></div>"
                + "<script>"
                + "var S=" + jsString(t(lang, "sending")) + ";"
                + "document.getElementById('f').onchange=function(){var f=this.files[0];if(!f)return;"
                + "var m=document.getElementById('msg'),b=document.getElementById('b'),p=document.getElementById('p');"
                + "m.className='';m.textContent=S+'...';b.style.display='block';p.style.width='0';"
                + "var x=new XMLHttpRequest();"
                + "x.open('POST','/upload?name='+encodeURIComponent(f.name));"
                + "x.upload.onprogress=function(e){if(e.lengthComputable){var k=Math.round(e.loaded*100/e.total);"
                + "p.style.width=k+'%';m.textContent=S+' '+k+'%';}};"
                + "x.onload=function(){var r={};try{r=JSON.parse(x.responseText)}catch(e){r={ok:false,msg:x.status}}"
                + "m.className=r.ok?'ok':'err';m.textContent=r.msg;p.style.width=r.ok?'100%':'0';};"
                + "x.onerror=function(){m.className='err';m.textContent=" + jsString(t(lang, "cut")) + ";};"
                + "x.send(f);this.value='';};"
                + "</script></main></body></html>";
    }

    private static String jsString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("</", "<\\/") + "\"";
    }
}
