package com.tvbox.app.import

import android.content.Context
import com.tvbox.app.core.TvLog

/**
 * 内置单文件 H5 导入页（assets/import_page.html，无任何外网依赖）。
 *
 * 页面是纯静态 HTML/CSS/JS：TV 端只做占位符替换
 * （__TOKEN_JSON__ / __PAIRING__ / __SERVICE__ / __EXPIRE_MIN__ / __MAX_MB__），
 * 通过 ServerSocket 直接返回，不引入 WebView 之外的东西。
 */
object ImportPage {

    private const val TAG = "ImportPage"
    private const val ASSET = "import_page.html"

    fun load(context: Context): String = try {
        context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (t: Throwable) {
        TvLog.e(TAG, "读取 $ASSET 失败，使用内置兜底页", t)
        FALLBACK
    }

    /** 无 Context（自检/服务端降级）时使用。 */
    val fallbackHtml: String get() = FALLBACK

    /** 兜底页：assets 被裁剪/损坏时仍能完成“填 URL → 导入”。 */
    private val FALLBACK = """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>导入播放源</title></head>
        <body style="font-family:system-ui,sans-serif;background:#111;color:#eee;padding:20px">
        <h2>导入播放源</h2>
        <p>服务地址：__SERVICE__（__EXPIRE_MIN__ 分钟内有效）</p>
        <div id="pair">
          <p>电视上显示的 6 位配对码：</p>
          <input id="code" inputmode="numeric" maxlength="6" style="font-size:22px;padding:8px;width:160px">
          <button onclick="pair()" style="font-size:18px;padding:8px 16px">配对</button>
        </div>
        <div id="main" style="display:none">
          <p>粘贴 MacCMS 地址或 tvapp.source JSON：</p>
          <textarea id="url" rows="4" style="width:100%;padding:8px"></textarea>
          <button onclick="sendUrl()" style="font-size:18px;padding:8px 16px">提交</button>
        </div>
        <pre id="out" style="white-space:pre-wrap"></pre>
        <script>
        var TOKEN = __TOKEN_JSON__;
        var PAIRING = "__PAIRING__";
        function out(t){document.getElementById('out').textContent=t;}
        function show(){ if(TOKEN){document.getElementById('pair').style.display='none';
          document.getElementById('main').style.display='block';}}
        function pair(){
          var code=document.getElementById('code').value.trim();
          fetch('/api/pair',{method:'POST',headers:{'Content-Type':'application/json'},
            body:JSON.stringify({code:code})}).then(function(r){return r.json()}).then(function(j){
              if(j.ok){TOKEN=j.token;show();out('配对成功');}else{out((j.error&&j.error.message)||'配对失败');}
            }).catch(function(e){out('网络错误：'+e);});
        }
        function sendUrl(){
          fetch('/api/import',{method:'POST',headers:{'Content-Type':'application/json'},
            body:JSON.stringify({token:TOKEN,url:document.getElementById('url').value.trim()})})
            .then(function(r){return r.json()}).then(function(j){out(j.summary||JSON.stringify(j));})
            .catch(function(e){out('网络错误：'+e);});
        }
        show();
        </script></body></html>
    """.trimIndent()
}
