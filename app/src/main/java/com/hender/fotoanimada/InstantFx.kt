package com.hender.fotoanimada

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filtros de instantánea que no se pueden hacer solo con color:
 * pixelado, VHS, cómic (puntos) y fuga de luz.
 *
 * En la cámara se ven en vivo con un shader (Android 13+); la foto final se
 * calcula aquí mismo píxel por píxel con la misma fórmula.
 */
object InstantFx {

    private const val PIXEL = """
uniform shader content;
uniform float cell;
half4 main(float2 p) {
    float2 c = (floor(p / cell) + 0.5) * cell;
    half4 col = content.eval(c);
    half3 q = floor(col.rgb * 7.0 + 0.5) / 7.0;
    return half4(q, 1.0);
}
"""

    private const val VHS = """
uniform shader content;
uniform float2 size;
half4 main(float2 p) {
    float off = size.x * 0.0045;
    half r = content.eval(p + float2(off, 0.0)).r;
    half g = content.eval(p).g;
    half b = content.eval(p - float2(off, 0.0)).b;
    half3 col = half3(r, g, b);
    half l = dot(col, half3(0.299, 0.587, 0.114));
    col = mix(col, half3(l), 0.18);
    col = col * 0.88 + half3(0.06, 0.05, 0.07);
    float line = 0.86 + 0.14 * sin(p.y * 1.5708);
    col *= half(line);
    float band = smoothstep(0.0, 0.02, abs(fract(p.y / size.y * 1.3 + 0.35) - 0.5) - 0.47);
    col = mix(col + 0.08, col, half(band));
    return half4(col, 1.0);
}
"""

    private const val COMIC = """
uniform shader content;
uniform float cell;
half4 main(float2 p) {
    float2 q = float2(p.x + p.y, p.y - p.x) * 0.70710678;
    float2 cc = (floor(q / cell) + 0.5) * cell;
    float2 back = float2(cc.x - cc.y, cc.x + cc.y) * 0.70710678;
    half4 src = content.eval(back);
    half lum = dot(src.rgb, half3(0.299, 0.587, 0.114));
    float d = length(q - cc) / (cell * 0.5);
    float radius = sqrt(max(0.0, 1.0 - float(lum))) * 1.2;
    float ink = 1.0 - smoothstep(radius - 0.15, radius + 0.15, d);
    half3 base = floor(content.eval(p).rgb * 4.0 + 0.5) / 4.0;
    base = min(base * 1.15 + 0.06, half3(1.0));
    half3 col = mix(base, base * 0.18, half(ink * 0.9));
    return half4(col, 1.0);
}
"""

    private const val LEAK = """
uniform shader content;
uniform float2 size;
half4 main(float2 p) {
    half3 c = content.eval(p).rgb;
    float2 uv = p / size;
    float l1 = smoothstep(0.85, 0.0, distance(uv, float2(-0.08, 0.12)));
    float l2 = smoothstep(0.65, 0.0, distance(uv, float2(1.08, 0.9)));
    half3 leak = half3(1.0, 0.42, 0.08) * half(l1 * 0.9) + half3(1.0, 0.12, 0.32) * half(l2 * 0.65);
    c = 1.0 - (1.0 - c) * (1.0 - leak);
    c = c * 0.92 + 0.05;
    return half4(c, 1.0);
}
"""

    private const val NIGHT_VISION = """
uniform shader content;
uniform float2 size;
half4 main(float2 p) {
    float3 c = float3(content.eval(p).rgb);
    float l = dot(c, float3(0.299, 0.587, 0.114));
    l = pow(min(1.0, l * 2.6), 0.8) + 0.03;
    float2 uv = (p / size - 0.5) * float2(1.0, size.y / size.x);
    float v = 1.0 - smoothstep(0.38, 0.85, length(uv));
    float n = fract(sin(dot(floor(p / 2.0), float2(12.9898, 78.233))) * 43758.5453);
    l = l * v + (n - 0.5) * 0.09;
    l *= 0.93 + 0.07 * sin(p.y * 1.2);
    return half4(half3(float3(0.22, 1.0, 0.32) * l), 1.0);
}
"""

    private const val TONE = """
uniform shader content;
uniform float gain;
uniform float lift;
uniform float sharp;
uniform float vivid;
uniform float3 wb;
half4 main(float2 p) {
    float3 x = float3(content.eval(p).rgb);
    // Nitidez suave respecto a los 4 vecinos (con sharp negativo, suaviza el grano de noche)
    float3 n = (float3(content.eval(p + float2(1.5, 0.0)).rgb) + float3(content.eval(p - float2(1.5, 0.0)).rgb) +
                float3(content.eval(p + float2(0.0, 1.5)).rgb) + float3(content.eval(p - float2(0.0, 1.5)).rgb)) * 0.25;
    x = clamp(x + (sharp + 0.35 * vivid) * (x - n), 0.0, 1.0);
    // De noche: el color corregido (el sensor tira a morado), igual que la foto
    x = clamp(x * wb, 0.0, 1.0);
    float l = max(dot(x, float3(0.299, 0.587, 0.114)), 0.002);
    // Brillo de noche: sube lo oscuro ×gain y deja el blanco en blanco (igual que la foto)
    float y = l * gain / (1.0 + (gain - 1.0) * l);
    // Sombras levantadas (como la mejora automática), luces casi intactas
    y = y + lift * y * (1.0 - y) * (1.0 - y);
    if (vivid > 0.0) {
        // Viva: contraste local respecto a lo de alrededor (poquito, para no marcar halos)
        float3 w = float3(content.eval(p + float2(9.0, 9.0)).rgb) + float3(content.eval(p + float2(-9.0, 9.0)).rgb) +
                   float3(content.eval(p + float2(9.0, -9.0)).rgb) + float3(content.eval(p + float2(-9.0, -9.0)).rgb);
        float lw = max(dot(w * 0.25, float3(0.299, 0.587, 0.114)), 0.002);
        float wg = smoothstep(0.025, 0.10, lw) * (1.0 - 0.6 * smoothstep(0.55, 0.95, lw));
        y *= pow(clamp(l / lw, 0.75, 1.33), 0.35 * vivid * wg);
        // Curva S suave: negros más profundos, blancos más limpios
        y = clamp(y, 0.0, 1.0);
        y = y + 0.20 * vivid * (y * y * (3.0 - 2.0 * y) - y);
    }
    // Mismo factor en los tres canales, sin pasarse del canal más alto (no cambia el color)
    float m = max(max(x.r, x.g), max(x.b, 0.002));
    float k = min(y / l, 1.0 / m);
    float3 o = x * k;
    if (vivid > 0.0) {
        // Viva: más color en lo que tiene poco; la piel casi no se toca
        float mx = max(max(o.r, o.g), max(o.b, 0.002));
        float mn = min(min(o.r, o.g), o.b);
        float amt = 0.30 * vivid * (1.0 - (mx - mn) / mx);
        if (o.r >= o.g && o.g >= o.b && o.r - o.b > 0.001) {
            float hue = 60.0 * (o.g - o.b) / (o.r - o.b);
            amt *= 1.0 - 0.75 * smoothstep(1.5, 10.5, hue) * (1.0 - smoothstep(39.5, 48.5, hue));
        }
        float ly = dot(o, float3(0.299, 0.587, 0.114));
        o = clamp(ly + (o - ly) * (1.0 + amt), 0.0, 1.0);
    }
    return half4(half3(o), 1.0);
}
"""

    /**
     * Vista previa con las sombras levantadas, parecida a como queda la foto
     * después de la mejora automática (las luces casi no se tocan).
     * [vivid] > 0: Viva prendida (más nitidez, contraste y color, igual que la foto).
     */
    fun tonePreview(
        gain: Float = 1f, lift: Float = 1.1f, sharp: Float = 0.45f, vivid: Float = 0f,
        wbR: Float = 1f, wbG: Float = 1f, wbB: Float = 1f,
    ): RenderEffect? {
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            val sh = (toneShader ?: RuntimeShader(TONE).also { toneShader = it }) as RuntimeShader
            sh.setFloatUniform("gain", gain)
            sh.setFloatUniform("lift", lift)
            sh.setFloatUniform("sharp", sharp)
            sh.setFloatUniform("vivid", vivid)
            sh.setFloatUniform("wb", wbR, wbG, wbB)
            RenderEffect.createRuntimeShaderEffect(sh, "content")
        } catch (e: Throwable) {
            null
        }
    }

    /** Se compila una sola vez (de noche se actualiza seguido con el brillo). */
    private var toneShader: Any? = null

    /**
     * Ojo de pez en vivo: la misma cuenta que [Fisheye] usa en la foto (gran angular o espejo
     * de tienda), para que lo que se ve sea lo que se guarda.
     */
    private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float style;
uniform float c;
uniform float radius;
uniform float rim;
uniform float vig;
uniform float back;
uniform float2 light; // giro del cel (cos, sin): el brillo del espejo queda arriba a la izquierda de quien mira

float smooth01(float a, float b, float v) {
    float t = clamp((v - a) / (b - a), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

float fishIn(float rho) {
    float ac = atan(c);
    return tan(clamp(rho, 0.0, 1.0) * ac) / c;
}

float fishSlope(float rho) {
    float ac = atan(c);
    float t = tan(clamp(rho, 0.0, 1.0) * ac);
    return ac * (1.0 + t * t) / c;
}

// Toma el color promediando 4 puntos cuando la zona va muy comprimida (sin dientes)
half3 grab(float2 q, float sc) {
    if (sc < 1.5) return content.eval(q).rgb;
    float o = 0.3 * sc;
    return (content.eval(q + float2(o, o * 0.5)).rgb + content.eval(q + float2(-o * 0.5, o)).rgb +
            content.eval(q + float2(-o, -o * 0.5)).rgb + content.eval(q + float2(o * 0.5, -o)).rgb) * 0.25;
}

half4 main(float2 p) {
    float2 ctr = size * 0.5;
    float2 d = p - ctr;
    float r = length(d);
    if (style < 1.5) {
        // Gran angular: toda la imagen curvada, las esquinas siguen siendo las esquinas
        float rho = r / length(ctr);
        float k = rho > 0.0001 ? fishIn(rho) / rho : atan(c) / c;
        float sc = max(fishSlope(rho), k);
        half3 col = grab(ctr + d * k, sc);
        return half4(col * (1.0 - vig * rho * rho * sqrt(rho)), 1.0);
    }
    // Espejo de tienda: la foto entera en un espejo redondo con aro, sobre el fondo oscuro
    float rc = radius * min(size.x, size.y);
    float rw = rim * rc;
    float aa = max(1.2, rc / 500.0);
    // La luz va con quien mira: el brillo y la sombra no giran con el cel de lado
    float2 dl = float2(d.x * light.x - d.y * light.y, d.x * light.y + d.y * light.x);
    float ringOut = 1.0 - smooth01(rc + rw - aa, rc + rw + aa, r);
    half3 col = half3(0.0);
    if (ringOut > 0.0) {
        // Aro metálico: claro arriba a la izquierda
        float ang = (-(dl.x + dl.y) / max(r, 1.0)) * 0.7071;
        float bevel = 1.0 - clamp(abs((r - rc - rw * 0.5) / (rw * 0.5)), 0.0, 1.0);
        float m = 0.42 + 0.30 * ang + 0.18 * bevel;
        half3 metal = half3(m, m, m);
        col = metal;
        if (r < rc + aa) {
            float rho = min(r / rc, 1.0);
            float2 u = r > 0.001 ? d / r : float2(1.0, 0.0);
            // Hasta dónde llega la foto en esa dirección (rectángulo con puntas redondeadas);
            // en el centro lo mismo para todos lados, así las caras no se aplastan
            float a = abs(u.x) / ctr.x;
            float b = abs(u.y) / ctr.y;
            float reach = 1.0 / max(sqrt(sqrt(a * a * a * a + b * b * b * b)), 0.000001);
            float r0 = min(ctr.x, ctr.y);
            float span = r0 + (reach - r0) * rho * rho;
            float f = fishIn(rho);
            float rin = f * span;
            float radial = (fishSlope(rho) * span + f * (reach - r0) * 2.0 * rho) / rc;
            float tang = rho > 0.001 ? rin / r : radial;
            half3 g = grab(ctr + u * rin, max(radial, tang));
            float edge = 1.0 - 0.22 * smooth01(0.72, 1.0, rho);
            float2 n = dl / rc + float2(0.32, 0.42);
            float gl = 0.13 * (1.0 - smooth01(0.05, 0.42, sqrt(n.x * n.x * 0.55 + n.y * n.y * 1.6)));
            g = g * edge;
            g = g + (half3(1.0) - g) * gl;
            col = g + (metal - g) * smooth01(rc - aa, rc + aa, r);
        }
        if (ringOut >= 1.0) return half4(col, 1.0);
    }
    // Fondo: la imagen borrosa (12 puntos en dos aros) y oscura, con la sombra del espejo debajo
    float br = 0.045 * min(size.x, size.y);
    float h = br * 0.7071; // diagonal del aro de afuera
    float q = br * 0.3536; // aro de adentro (la mitad), en diagonal
    half3 bg = content.eval(p + float2(br, 0.0)).rgb + content.eval(p - float2(br, 0.0)).rgb +
               content.eval(p + float2(0.0, br)).rgb + content.eval(p - float2(0.0, br)).rgb +
               content.eval(p + float2(h, h)).rgb + content.eval(p - float2(h, h)).rgb +
               content.eval(p + float2(h, -h)).rgb + content.eval(p - float2(h, -h)).rgb +
               content.eval(p + float2(q, q)).rgb + content.eval(p - float2(q, q)).rgb +
               content.eval(p + float2(q, -q)).rgb + content.eval(p - float2(q, -q)).rgb;
    bg *= back / 12.0;
    float2 ds = float2(dl.x, dl.y - rc * 0.035);
    float shadowR = rc + rw;
    bg *= 1.0 - 0.55 * (1.0 - smooth01(shadowR * 0.98, shadowR * 1.16, length(ds)));
    return half4(bg + (col - bg) * ringOut, 1.0);
}
"""

    /**
     * Ojo de pez para la vista previa ([Fisheye.WIDE] o [Fisheye.MIRROR]) sobre una vista de w×h.
     * [degrees] es el giro del cel (0, 90, 180, 270): el brillo del espejo queda arriba a la
     * izquierda de quien mira, igual que en la foto (que se guarda derecha).
     */
    fun lensPreview(style: Int, w: Int, h: Int, degrees: Int = 0): RenderEffect? {
        if (Build.VERSION.SDK_INT < 33 || style == Fisheye.OFF || w <= 0 || h <= 0) return null
        return try {
            val sh = (lensShader ?: RuntimeShader(LENS).also { lensShader = it }) as RuntimeShader
            val rad = Math.toRadians(degrees.toDouble())
            sh.setFloatUniform("size", w.toFloat(), h.toFloat())
            sh.setFloatUniform("style", style.toFloat())
            sh.setFloatUniform("c", if (style == Fisheye.WIDE) Fisheye.WIDE_C else Fisheye.MIRROR_C)
            sh.setFloatUniform("radius", Fisheye.MIRROR_RADIUS)
            sh.setFloatUniform("rim", Fisheye.RIM)
            sh.setFloatUniform("vig", Fisheye.WIDE_VIGNETTE)
            sh.setFloatUniform("back", Fisheye.BACK_LIGHT)
            sh.setFloatUniform("light", Math.cos(rad).toFloat(), Math.sin(rad).toFloat())
            RenderEffect.createRuntimeShaderEffect(sh, "content")
        } catch (e: Throwable) {
            null
        }
    }

    private var lensOk: Boolean? = null

    /** Si este cel puede mostrar el ojo de pez en la vista previa (Android 13 o más nuevo). */
    fun lensSupported(): Boolean {
        lensOk?.let { return it }
        val ok = if (Build.VERSION.SDK_INT < 33) false else try {
            RuntimeShader(LENS) // solo para ver que compila (la vista previa usa su propia copia)
            true
        } catch (e: Throwable) {
            false
        }
        lensOk = ok
        return ok
    }

    private var lensShader: Any? = null

    /** Visión nocturna en vivo (verde, con grano y viñeta). */
    fun nightVisionPreview(w: Int, h: Int): RenderEffect? {
        if (Build.VERSION.SDK_INT < 33 || w <= 0 || h <= 0) return null
        return try {
            RenderEffect.createRuntimeShaderEffect(
                RuntimeShader(NIGHT_VISION).apply { setFloatUniform("size", w.toFloat(), h.toFloat()) }, "content"
            )
        } catch (e: Throwable) {
            null
        }
    }

    /** Respaldo de color para cels sin shaders. */
    fun nightVisionMatrix(): android.graphics.ColorMatrix {
        val g = 2.4f
        val l = floatArrayOf(0.299f * g, 0.587f * g, 0.114f * g)
        return android.graphics.ColorMatrix(floatArrayOf(
            l[0] * 0.22f, l[1] * 0.22f, l[2] * 0.22f, 0f, 6f,
            l[0], l[1], l[2], 0f, 8f,
            l[0] * 0.32f, l[1] * 0.32f, l[2] * 0.32f, 0f, 6f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    /** Visión nocturna para la foto final (misma fórmula que el shader). */
    fun nightVision(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val rnd = java.util.Random(11)
        val asp = h.toFloat() / w
        for (y in 0 until h) {
            val line = 0.93f + 0.07f * sin((y + 0.5f) * 1.2f * w / 1080f)
            for (x in 0 until w) {
                val c = px[y * w + x]
                var l = 0.299f * r(c) + 0.587f * g(c) + 0.114f * b(c)
                l = Math.pow(min(1f, l * 2.6f).toDouble(), 0.8).toFloat() + 0.03f
                val ux = (x + 0.5f) / w - 0.5f
                val uy = ((y + 0.5f) / h - 0.5f) * asp
                val v = 1f - smooth(0.38f, 0.85f, sqrt(ux * ux + uy * uy))
                l = (l * v + (rnd.nextFloat() - 0.5f) * 0.09f) * line
                px[y * w + x] = pack(0.22f * l, l, 0.32f * l)
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Efecto en vivo para la vista previa (null si el cel no lo soporta). */
    fun preview(look: InstantLook, w: Int, h: Int): RenderEffect? {
        if (Build.VERSION.SDK_INT < 33 || w <= 0 || h <= 0) return null
        return try {
            val shader = when (look) {
                InstantLook.PIXEL -> RuntimeShader(PIXEL).apply { setFloatUniform("cell", w / 48f) }
                InstantLook.VHS -> RuntimeShader(VHS).apply { setFloatUniform("size", w.toFloat(), h.toFloat()) }
                InstantLook.COMIC -> RuntimeShader(COMIC).apply { setFloatUniform("cell", w / 70f) }
                InstantLook.LEAK -> RuntimeShader(LEAK).apply { setFloatUniform("size", w.toFloat(), h.toFloat()) }
                else -> return null
            }
            RenderEffect.createRuntimeShaderEffect(shader, "content")
        } catch (e: Throwable) {
            Log.w("InstantFx", "Sin vista previa del filtro: ${e.message}")
            null
        }
    }

    /** Aplica el filtro a una foto (misma fórmula que el shader). */
    fun render(look: InstantLook, src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val inPx = IntArray(w * h)
        src.getPixels(inPx, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        when (look) {
            InstantLook.PIXEL -> pixel(inPx, out, w, h, w / 48f)
            InstantLook.VHS -> vhs(inPx, out, w, h)
            InstantLook.COMIC -> comic(inPx, out, w, h, w / 70f)
            InstantLook.LEAK -> leak(inPx, out, w, h)
            else -> System.arraycopy(inPx, 0, out, 0, inPx.size)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun r(c: Int) = ((c shr 16) and 255) / 255f
    private fun g(c: Int) = ((c shr 8) and 255) / 255f
    private fun b(c: Int) = (c and 255) / 255f
    private fun pack(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
    private fun to8(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    private fun at(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int =
        px[y.toInt().coerceIn(0, h - 1) * w + x.toInt().coerceIn(0, w - 1)]
    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun pixel(src: IntArray, out: IntArray, w: Int, h: Int, cell: Float) {
        for (y in 0 until h) for (x in 0 until w) {
            val cx = (floor((x + 0.5f) / cell) + 0.5f) * cell
            val cy = (floor((y + 0.5f) / cell) + 0.5f) * cell
            val c = at(src, w, h, cx, cy)
            out[y * w + x] = pack(
                floor(r(c) * 7f + 0.5f) / 7f, floor(g(c) * 7f + 0.5f) / 7f, floor(b(c) * 7f + 0.5f) / 7f,
            )
        }
    }

    private fun vhs(src: IntArray, out: IntArray, w: Int, h: Int) {
        val off = w * 0.0045f
        for (y in 0 until h) {
            val line = 0.86f + 0.14f * sin((y + 0.5f) * 1.5708f)
            val fr = ((y + 0.5f) / h * 1.3f + 0.35f).let { it - floor(it) }
            val band = smooth(0f, 0.02f, abs(fr - 0.5f) - 0.47f)
            for (x in 0 until w) {
                var rr = r(at(src, w, h, x + off, y.toFloat()))
                var gg = g(src[y * w + x])
                var bb = b(at(src, w, h, x - off, y.toFloat()))
                val l = 0.299f * rr + 0.587f * gg + 0.114f * bb
                rr = (rr + (l - rr) * 0.18f) * 0.88f + 0.06f
                gg = (gg + (l - gg) * 0.18f) * 0.88f + 0.05f
                bb = (bb + (l - bb) * 0.18f) * 0.88f + 0.07f
                rr *= line; gg *= line; bb *= line
                val add = 0.08f * (1f - band)
                out[y * w + x] = pack(rr + add, gg + add, bb + add)
            }
        }
    }

    private fun comic(src: IntArray, out: IntArray, w: Int, h: Int, cell: Float) {
        val k = 0.70710678f
        for (y in 0 until h) for (x in 0 until w) {
            val px = x + 0.5f
            val py = y + 0.5f
            val qx = (px + py) * k
            val qy = (py - px) * k
            val ccx = (floor(qx / cell) + 0.5f) * cell
            val ccy = (floor(qy / cell) + 0.5f) * cell
            val bx = (ccx - ccy) * k
            val by = (ccx + ccy) * k
            val s = at(src, w, h, bx, by)
            val lum = 0.299f * r(s) + 0.587f * g(s) + 0.114f * b(s)
            val dx = qx - ccx
            val dy = qy - ccy
            val d = sqrt(dx * dx + dy * dy) / (cell * 0.5f)
            val radius = sqrt(max(0f, 1f - lum)) * 1.2f
            val ink = (1f - smooth(radius - 0.15f, radius + 0.15f, d)) * 0.9f
            val c = src[y * w + x]
            var br = min(1f, floor(r(c) * 4f + 0.5f) / 4f * 1.15f + 0.06f)
            var bg = min(1f, floor(g(c) * 4f + 0.5f) / 4f * 1.15f + 0.06f)
            var bb = min(1f, floor(b(c) * 4f + 0.5f) / 4f * 1.15f + 0.06f)
            br += (br * 0.18f - br) * ink
            bg += (bg * 0.18f - bg) * ink
            bb += (bb * 0.18f - bb) * ink
            out[y * w + x] = pack(br, bg, bb)
        }
    }

    private fun leak(src: IntArray, out: IntArray, w: Int, h: Int) {
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + 0.5f) / w
            val v = (y + 0.5f) / h
            val d1 = sqrt((u + 0.08f) * (u + 0.08f) + (v - 0.12f) * (v - 0.12f))
            val d2 = sqrt((u - 1.08f) * (u - 1.08f) + (v - 0.9f) * (v - 0.9f))
            val l1 = smooth(0.85f, 0f, d1) * 0.9f
            val l2 = smooth(0.65f, 0f, d2) * 0.65f
            val lr = min(1f, 1.0f * l1 + 1.0f * l2)
            val lg = min(1f, 0.42f * l1 + 0.12f * l2)
            val lb = min(1f, 0.08f * l1 + 0.32f * l2)
            val c = src[y * w + x]
            val rr = 1f - (1f - r(c)) * (1f - lr)
            val gg = 1f - (1f - g(c)) * (1f - lg)
            val bb = 1f - (1f - b(c)) * (1f - lb)
            out[y * w + x] = pack(rr * 0.92f + 0.05f, gg * 0.92f + 0.05f, bb * 0.92f + 0.05f)
        }
    }
}
