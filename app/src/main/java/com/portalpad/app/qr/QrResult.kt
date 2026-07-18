package com.portalpad.app.qr

/**
 * Classifies decoded QR/barcode text into a confirmable action. The confirm
 * popup shows [title]/[detail] and runs [kind]; URL/app actions open on the
 * EXTERNAL display (handled by the caller via launch-display options).
 */
data class QrResult(
    val raw: String,
    val kind: Kind,
    val title: String,
    val detail: String,
    /** Wi-Fi parse (WIFI kind only). */
    val wifiSsid: String? = null,
    val wifiPassword: String? = null,
    val wifiType: String? = null,
    val wifiHidden: Boolean = false,
    /** URL/app target (URL kind). */
    val url: String? = null,
) {
    enum class Kind { URL, WIFI, TEXT }

    companion object {
        fun classify(raw: String): QrResult {
            val t = raw.trim()
            // Wi-Fi: "WIFI:T:WPA;S:ssid;P:pass;H:true;;" (QR convention).
            if (t.startsWith("WIFI:", ignoreCase = true)) {
                val body = t.substring(5)
                val fields = parseWifiFields(body)
                val ssid = fields["S"]
                if (!ssid.isNullOrEmpty()) {
                    return QrResult(
                        raw = raw,
                        kind = Kind.WIFI,
                        title = "Join Wi-Fi network",
                        detail = ssid,
                        wifiSsid = ssid,
                        wifiPassword = fields["P"],
                        wifiType = fields["T"] ?: "WPA",
                        wifiHidden = fields["H"].equals("true", ignoreCase = true),
                    )
                }
            }
            // URL: http/https, or a bare domain-looking token.
            val looksUrl = t.startsWith("http://", true) || t.startsWith("https://", true)
            val bareDomain = !t.contains(" ") && Regex("^[\\w.-]+\\.[a-zA-Z]{2,}(/.*)?$").matches(t)
            if (looksUrl || bareDomain) {
                val url = if (looksUrl) t else "https://$t"
                return QrResult(
                    raw = raw,
                    kind = Kind.URL,
                    title = "Open link",
                    detail = url,
                    url = url,
                )
            }
            // Everything else: plain text / product code — copy or web-search.
            return QrResult(
                raw = raw,
                kind = Kind.TEXT,
                title = "Scanned text",
                detail = t,
            )
        }

        /** WIFI payload fields are ';'-separated K:V, with '\;' / '\,' / '\:'
         *  escapes inside values. */
        private fun parseWifiFields(body: String): Map<String, String> {
            val out = HashMap<String, String>()
            val sb = StringBuilder()
            var key: String? = null
            var i = 0
            fun flush() {
                if (key != null) out[key!!.uppercase()] = sb.toString()
                key = null; sb.setLength(0)
            }
            while (i < body.length) {
                val c = body[i]
                when {
                    c == '\\' && i + 1 < body.length -> { sb.append(body[i + 1]); i++ }
                    c == ':' && key == null -> { key = sb.toString(); sb.setLength(0) }
                    c == ';' -> flush()
                    else -> sb.append(c)
                }
                i++
            }
            flush()
            return out
        }
    }
}
