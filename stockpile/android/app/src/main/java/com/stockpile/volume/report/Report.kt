package com.stockpile.volume.report

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.stockpile.volume.chain.Xrpl
import com.stockpile.volume.data.ChainAnchor
import com.stockpile.volume.data.MeasurementRecord
import com.stockpile.volume.data.Project
import com.stockpile.volume.data.ProjectStore
import com.stockpile.volume.measure.MeasurementSaver
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class ReportLang(val code: String, val label: String, val locale: Locale) {
    EN("EN", "English", Locale.UK),
    DE("DE", "Deutsch", Locale.GERMANY),
    CS("CS", "Čeština", Locale("cs", "CZ")),
    ;

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: EN
    }
}

/** Final measurement protocol as an A4 PDF in English (default), German or Czech. */
object Report {

    // ------------------------------------------------------------------ texts

    private val T: Map<String, Triple<String, String, String>> = mapOf(
        "title" to Triple("Stockpile Volume Measurement Report", "Messprotokoll Haldenvolumen", "Protokol měření objemu hromady"),
        "pile" to Triple("Stockpile", "Halde", "Hromada"),
        "measured" to Triple("Measured", "Gemessen", "Změřeno"),
        "method" to Triple("Method", "Methode", "Metoda"),
        "device" to Triple("Device / app", "Gerät / App", "Zařízení / aplikace"),
        "results" to Triple("Results", "Ergebnisse", "Výsledky"),
        "volume" to Triple("Volume", "Volumen", "Objem"),
        "footprint" to Triple("Footprint area", "Grundfläche", "Půdorys"),
        "maxh" to Triple("Maximum height", "Maximale Höhe", "Max. výška"),
        "density" to Triple("Bulk density", "Schüttdichte", "Objemová hmotnost"),
        "mass" to Triple("Mass", "Masse", "Hmotnost"),
        "waterk" to Triple("Water coefficient (by volume)", "Wasserkoeffizient (Volumenanteil)", "Koeficient vody (objemový)"),
        "water" to Triple("Water", "Wasser", "Voda"),
        "dryv" to Triple("Volume without water", "Volumen ohne Wasser", "Objem bez vody"),
        "drym" to Triple("Dry mass", "Trockenmasse", "Sušina"),
        "location" to Triple("Location", "Standort", "Poloha"),
        "coords" to Triple("GPS coordinates (WGS 84)", "GPS-Koordinaten (WGS 84)", "GPS souřadnice (WGS 84)"),
        "accuracy" to Triple("Estimated accuracy", "Geschätzte Genauigkeit", "Odhad přesnosti"),
        "averaged" to Triple("averaged from %d fixes", "gemittelt aus %d Messungen", "průměr z %d měření"),
        "single" to Triple("single fix", "Einzelmessung", "jednorázové měření"),
        "map" to Triple("Map", "Karte", "Mapa"),
        "nogps" to Triple("not recorded", "nicht erfasst", "nezaznamenána"),
        "boundary" to Triple("Volume boundary", "Volumenbegrenzung", "Hranice objemu"),
        "walked" to Triple("walked path around the pile", "begangener Weg um die Halde", "trasa obchůzky kolem hromady"),
        "circle" to Triple("circle drawn by the operator, r = %s m", "vom Bediener gezeichneter Kreis, r = %s m", "kružnice nakreslená obsluhou, r = %s m"),
        "polygon" to Triple("polygon of %s straight lines drawn by the operator", "vom Bediener gezeichnetes Polygon aus %s Geraden", "mnohoúhelník z %s přímek nakreslený obsluhou"),
        "barea" to Triple("Boundary area", "Fläche der Begrenzung", "Plocha hranice"),
        "quality" to Triple("Measurement quality", "Messqualität", "Kvalita měření"),
        "coverage" to Triple("Area actually scanned", "Tatsächlich gescannte Fläche", "Skutečně naskenovaná plocha"),
        "slope" to Triple("Ground slope / flatness", "Geländeneigung / Ebenheit", "Sklon / rovinnost terénu"),
        "cell" to Triple("Grid cell size", "Rasterweite", "Velikost buňky mřížky"),
        "path" to Triple("Walked path length", "Länge des Rundgangs", "Délka obchůzky"),
        "points" to Triple("3D points (voxels)", "3D-Punkte (Voxel)", "3D body (voxely)"),
        "inputs" to Triple("Entered dimensions", "Eingegebene Maße", "Zadané rozměry"),
        "warnings" to Triple("Notes and warnings", "Hinweise und Warnungen", "Poznámky a upozornění"),
        "heightmap" to Triple("Height map (top view, blue = low, red = high)", "Höhenkarte (Draufsicht, blau = niedrig, rot = hoch)", "Výšková mapa (pohled shora, modrá = nízko, červená = vysoko)"),
        "integrity" to Triple("Integrity and blockchain record", "Integrität und Blockchain-Eintrag", "Integrita a zápis v blockchainu"),
        "sha" to Triple("SHA-256 of record.json", "SHA-256 von record.json", "SHA-256 souboru record.json"),
        "network" to Triple("Network", "Netzwerk", "Síť"),
        "status" to Triple("Status", "Status", "Stav"),
        "tx" to Triple("Transaction", "Transaktion", "Transakce"),
        "ledger" to Triple("Ledger", "Ledger", "Ledger"),
        "account" to Triple("Signed by account", "Signiert von Konto", "Podepsal účet"),
        "explorer" to Triple("Explorer", "Explorer", "Průzkumník"),
        "validated" to Triple("written and validated on the XRP Ledger", "im XRP Ledger geschrieben und validiert", "zapsáno a potvrzeno v XRP Ledger"),
        "notanchored" to Triple("not written to the blockchain", "nicht in die Blockchain geschrieben", "nezapsáno do blockchainu"),
        "pendinga" to Triple("waiting for signature / validation", "wartet auf Signatur / Validierung", "čeká na podpis / potvrzení"),
        "verify" to Triple(
            "Verification: the SHA-256 of the file record.json must equal the \"sha256\" value in the memo of the transaction above.",
            "Prüfung: Der SHA-256 der Datei record.json muss dem Wert \"sha256\" im Memo der obigen Transaktion entsprechen.",
            "Ověření: SHA-256 souboru record.json se musí shodovat s hodnotou \"sha256\" v poznámce (memo) výše uvedené transakce.",
        ),
        "generated" to Triple("Generated", "Erstellt", "Vytvořeno"),
        "page" to Triple("Page", "Seite", "Strana"),
        "m_scan_raw" to Triple("3D scan (ARCore Raw Depth)", "3D-Scan (ARCore Raw Depth)", "3D sken (ARCore Raw Depth)"),
        "m_scan" to Triple("3D scan (ARCore Depth)", "3D-Scan (ARCore Depth)", "3D sken (ARCore Depth)"),
        "m_scan_pts" to Triple("3D scan (ARCore feature points, low accuracy)", "3D-Scan (ARCore Merkmalspunkte, geringe Genauigkeit)", "3D sken (body obrazu ARCore, nízká přesnost)"),
        "m_cone" to Triple("Manual – cone", "Manuell – Kegel", "Ruční – kužel"),
        "m_windrow" to Triple("Manual – windrow", "Manuell – Miete (Längshalde)", "Ruční – val"),
        "m_frustum" to Triple("Manual – truncated pyramid", "Manuell – Pyramidenstumpf", "Ruční – komolý jehlan"),
        "height_m" to Triple("Height", "Höhe", "Výška"),
        "perimeter_m" to Triple("Base perimeter", "Umfang am Fuß", "Obvod paty"),
        "diameter_m" to Triple("Base diameter", "Durchmesser am Fuß", "Průměr paty"),
        "length_m" to Triple("Base length", "Länge am Fuß", "Délka paty"),
        "width_m" to Triple("Base width", "Breite am Fuß", "Šířka paty"),
        "top_length_m" to Triple("Top length", "Länge oben", "Délka horní plochy"),
        "top_width_m" to Triple("Top width", "Breite oben", "Šířka horní plochy"),
    )

    /** Warnings are stored in Czech in the record; translated here by their opening words. */
    private val WARN: List<Pair<String, Triple<String, String, String>>> = listOf(
        "Terén kolem hromady je nerovný" to Triple(
            "Uneven ground around the pile – the base plane may be inaccurate.",
            "Unebener Boden um die Halde – die Basisebene kann ungenau sein.", ""),
        "Sklon terénu" to Triple(
            "Steep ground slope – check that the ground really is that steep.",
            "Starke Geländeneigung – bitte prüfen, ob das Gelände wirklich so steil ist.", ""),
        "Naskenováno jen" to Triple(
            "Only part of the area was scanned; the rest was interpolated.",
            "Nur ein Teil der Fläche wurde gescannt; der Rest wurde interpoliert.", ""),
        "Velký objem pod úrovní terénu" to Triple(
            "Large volume below ground level – the ground inside is lower than around it.",
            "Großes Volumen unter Geländeniveau – der Boden innen liegt tiefer als außen.", ""),
        "Hromada sahá až k nakreslené hranici" to Triple(
            "The pile reaches the drawn boundary – the boundary may be too tight.",
            "Die Halde reicht bis an die gezeichnete Begrenzung – diese ist evtl. zu eng.", ""),
        "Hromada sahá až k hranici" to Triple(
            "The pile reaches the walked boundary – walk further from the toe next time.",
            "Die Halde reicht bis an die Begrenzung – beim nächsten Mal weiter vom Fuß entfernt gehen.", ""),
        "Ruční měření" to Triple(
            "Manual measurement: volume calculated from the entered dimensions and selected shape, not from a scan (typically ±10–20 %).",
            "Manuelle Messung: Volumen aus eingegebenen Maßen und gewählter Form berechnet, nicht gescannt (typisch ±10–20 %).", ""),
    )

    private fun t(lang: ReportLang, key: String): String {
        val v = T[key] ?: return key
        return when (lang) { ReportLang.EN -> v.first; ReportLang.DE -> v.second; ReportLang.CS -> v.third }
    }

    private fun warning(lang: ReportLang, w: String): String {
        if (lang == ReportLang.CS) return w
        val hit = WARN.firstOrNull { w.startsWith(it.first) }?.second ?: return w
        return if (lang == ReportLang.EN) hit.first else hit.second
    }

    private fun methodName(lang: ReportLang, m: String): String {
        val base = m.substringBefore("+")
        val key = when (base) {
            "arcore-raw-depth" -> "m_scan_raw"; "arcore-depth" -> "m_scan"; "arcore-feature-points" -> "m_scan_pts"
            "manual-cone" -> "m_cone"; "manual-windrow" -> "m_windrow"; "manual-frustum" -> "m_frustum"
            else -> null
        }
        return key?.let { t(lang, it) } ?: m
    }

    // ------------------------------------------------------------------ PDF

    private const val W = 595; private const val H = 842; private const val M = 42f

    private class Writer(val doc: PdfDocument, val lang: ReportLang, val footer: String) {
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var c: Canvas
        var y = 0f
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.rgb(30, 30, 30) }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val head = Paint(bold).apply { textSize = 12.5f; color = Color.rgb(31, 78, 95) }
        val small = Paint(text).apply { textSize = 8f; color = Color.GRAY }
        val mono = Paint(text).apply { typeface = Typeface.MONOSPACE; textSize = 8.5f }
        val line = Paint().apply { color = Color.rgb(200, 205, 210); strokeWidth = 0.7f }

        fun newPage() {
            if (pageNo > 0) finishPage()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page.canvas
            y = M
        }

        fun finishPage() {
            c.drawLine(M, H - 32f, W - M, H - 32f, line)
            c.drawText(footer, M, H - 20f, small)
            val p = "${t(lang, "page")} $pageNo"
            c.drawText(p, W - M - small.measureText(p), H - 20f, small)
            doc.finishPage(page)
        }

        fun ensure(h: Float) { if (y + h > H - 48f) newPage() }

        fun wrap(s: String, p: Paint, width: Float): List<String> {
            val out = ArrayList<String>()
            s.split("\n").forEach { para ->
                var rest = para
                while (rest.isNotEmpty()) {
                    var n = maxOf(1, p.breakText(rest, true, width, null))
                    if (n < rest.length) {
                        val sp = rest.lastIndexOf(' ', n)
                        if (sp > 0) n = sp
                    }
                    out += rest.substring(0, n).trimEnd()
                    rest = rest.substring(n).trimStart()
                }
                if (para.isEmpty()) out += ""
            }
            return out
        }

        fun para(s: String, p: Paint = text, gap: Float = 4f) {
            wrap(s, p, W - 2 * M).forEach { l -> ensure(p.textSize + 3); y += p.textSize + 3; c.drawText(l, M, y, p) }
            y += gap
        }

        fun section(title: String) {
            ensure(40f); y += 10f
            c.drawText(title, M, y + head.textSize, head)
            y += head.textSize + 5f
            c.drawLine(M, y, W - M, y, line); y += 4f
        }

        fun row(label: String, value: String, valuePaint: Paint = bold) {
            val colX = M + 190f
            val lines = wrap(value, valuePaint, W - M - colX)
            ensure(14f * lines.size)
            lines.forEachIndexed { i, l ->
                y += 14f
                if (i == 0) c.drawText(label, M, y, text)
                c.drawText(l, colX, y, valuePaint)
            }
        }
    }

    fun build(context: Context, project: Project, lang: ReportLang): File {
        val m = project.measurement ?: error("no measurement")
        val store = ProjectStore.get(context)
        val nf = { v: Double, d: Int -> String.format(lang.locale, "%,.${d}f", v) }
        val dt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(lang.locale).withZone(ZoneId.systemDefault())
        fun date(iso: String) = runCatching { dt.format(Instant.parse(iso)) }.getOrDefault(iso)

        val doc = PdfDocument()
        val footer = "${t(lang, "generated")}: ${dt.format(Instant.now())} · ${m.app} · ${m.id}"
        val w = Writer(doc, lang, footer)
        w.newPage()

        // ---- Header
        w.c.drawText(t(lang, "title"), M, w.y + 18f, Paint(w.bold).apply { textSize = 18f; color = Color.rgb(31, 78, 95) })
        w.y += 30f
        w.row(t(lang, "pile"), m.project)
        w.row(t(lang, "measured"), date(m.measuredAt))
        w.row(t(lang, "method"), methodName(lang, m.method), w.text)
        w.row(t(lang, "device"), "${m.device} · ${m.app}", w.text)

        // ---- Results
        w.section(t(lang, "results"))
        w.row(t(lang, "volume"), "${nf(m.volumeM3, 2)} m³", Paint(w.bold).apply { textSize = 13f })
        w.row(t(lang, "footprint"), "${nf(m.footprintM2, 1)} m²")
        w.row(t(lang, "maxh"), "${nf(m.maxHeightM, 2)} m")
        m.densityTM3?.let { w.row(t(lang, "density"), "${nf(it, 3)} t/m³") }
        m.tonnes?.let { w.row(t(lang, "mass"), "${nf(it, 2)} t") }
        m.waterCoef?.let { k ->
            w.row(t(lang, "waterk"), "${nf(k, 3)} (${nf(k * 100, 1)} %)")
            m.waterM3?.let { w.row(t(lang, "water"), "${nf(it, 2)} m³ ≈ ${nf(it, 2)} t") }
            m.dryVolumeM3?.let { w.row(t(lang, "dryv"), "${nf(it, 2)} m³") }
            m.dryTonnes?.let { w.row(t(lang, "drym"), "${nf(it, 2)} t") }
        }

        // ---- Location
        w.section(t(lang, "location"))
        val g = m.location
        if (g == null) w.row(t(lang, "coords"), t(lang, "nogps"), w.text)
        else {
            w.row(t(lang, "coords"), String.format(Locale.US, "%.5f, %.5f", g.lat, g.lon))
            w.row(t(lang, "accuracy"), (g.accuracyM?.let { "± ${nf(it, 1)} m" } ?: "–") + " · " +
                if (g.source == "gps-average") t(lang, "averaged").format(g.samples ?: 0) else t(lang, "single"), w.text)
            w.row(t(lang, "map"), String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", g.lat, g.lon), w.text)
        }

        // ---- Boundary + quality
        w.section(t(lang, "quality"))
        val b = m.boundary
        val bText = when {
            m.inputs != null -> "–"
            b == null -> t(lang, "walked")
            b.startsWith("circle") -> t(lang, "circle").format(b.substringAfter("r=").substringBefore(" m"))
            b.startsWith("polygon") -> t(lang, "polygon").format(b.substringAfter("polygon ").substringBefore(" pts"))
            else -> b
        }
        w.row(t(lang, "boundary"), bText, w.text)
        if (m.inputs == null) {
            w.row(t(lang, "barea"), "${nf(m.boundaryAreaM2, 1)} m²", w.text)
            w.row(t(lang, "coverage"), "${nf(m.coveragePct, 0)} %", w.text)
            w.row(t(lang, "slope"), "${nf(m.baseSlopePct, 1)} % / ± ${nf(m.baseRmseM * 100, 0)} cm", w.text)
            w.row(t(lang, "cell"), "${nf(m.cellSizeM * 100, 1)} cm", w.text)
            w.row(t(lang, "path"), "${nf(m.pathLengthM, 1)} m", w.text)
            w.row(t(lang, "points"), nf(m.voxels.toDouble(), 0), w.text)
        } else {
            m.inputs.forEach { (k, v) -> w.row(t(lang, k), "${nf(v, 2)} m", w.text) }
        }
        if (m.warnings.isNotEmpty()) {
            w.section(t(lang, "warnings"))
            m.warnings.forEach { w.para("• " + warning(lang, it)) }
        }

        // ---- Height map
        val png = File(MeasurementSaver.measurementDir(store, project.id), MeasurementSaver.PREVIEW_FILE)
        BitmapFactory.decodeFile(png.absolutePath)?.let { bmp ->
            val maxW = W - 2 * M; val maxH = 300f
            val scale = minOf(maxW / bmp.width, maxH / bmp.height)
            val dw = bmp.width * scale; val dh = bmp.height * scale
            w.section(t(lang, "heightmap"))
            w.ensure(dh + 8)
            val left = M + (maxW - dw) / 2
            w.c.drawBitmap(bmp, null, Rect(left.toInt(), (w.y + 4).toInt(), (left + dw).toInt(), (w.y + 4 + dh).toInt()), null)
            w.y += dh + 10
        }

        // ---- Integrity + blockchain
        w.section(t(lang, "integrity"))
        w.row(t(lang, "sha"), project.recordSha256 ?: "–", w.mono)
        val a = project.anchor
        if (a != null && project.anchorMatches) {
            w.row(t(lang, "network"), if (a.network == Xrpl.TESTNET) "XRP Ledger Testnet" else "XRP Ledger Mainnet", w.text)
            w.row(t(lang, "status"), if (a.status == ChainAnchor.VALIDATED) t(lang, "validated") else t(lang, "pendinga"), w.text)
            a.txid?.let { w.row(t(lang, "tx"), it, w.mono) }
            a.ledgerIndex?.let { w.row(t(lang, "ledger"), "#$it", w.text) }
            a.account?.let { w.row(t(lang, "account"), it, w.mono) }
            a.txid?.let { w.row(t(lang, "explorer"), Xrpl.explorerUrl(a.network, it), w.text) }
            w.y += 6f
            w.para(t(lang, "verify"), w.small)
        } else {
            w.row(t(lang, "status"), t(lang, "notanchored"), w.text)
        }

        w.finishPage()
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safe = m.project.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(40).ifBlank { "stockpile" }
        val out = File(dir, "report_${safe}_${lang.code}.pdf")
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }
}
