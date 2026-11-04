package com.hender.fotoanimada

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Hojas que suben desde abajo (estilo iPhone), en vez de los menús grises de Android.
 */
object Sheet {

    class Item(
        val label: String,
        val icon: Int = 0,
        val checked: Boolean = false,
        val destructive: Boolean = false,
        val subtitle: String? = null,
        val onClick: () -> Unit,
    )

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun base(a: Activity, title: String?, subtitle: String?): Pair<Dialog, LinearLayout> {
        val dialog = Dialog(a)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 22))
        }
        root.addView(View(a).apply { setBackgroundResource(R.drawable.bg_handle) },
            LinearLayout.LayoutParams(dp(a, 38), dp(a, 5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(a, 14)
            })
        if (title != null) {
            root.addView(TextView(a).apply {
                text = title
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(a, 6), 0, 0, if (subtitle == null) dp(a, 10) else dp(a, 2))
            })
        }
        if (subtitle != null) {
            root.addView(TextView(a).apply {
                text = subtitle
                textSize = 13f
                setTextColor(0x8CFFFFFF.toInt())
                setPadding(dp(a, 6), 0, 0, dp(a, 12))
            })
        }
        return dialog to root
    }

    private fun show(a: Activity, dialog: Dialog, root: View) {
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setDimAmount(0.45f)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Lista de acciones con ícono, como el menú de compartir de iPhone. */
    fun actions(a: Activity, title: String?, items: List<Item>, subtitle: String? = null) {
        val (dialog, root) = base(a, title, subtitle)
        val group = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_segmented)
        }
        items.forEachIndexed { i, it ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(a, 16), dp(a, 13), dp(a, 16), dp(a, 13))
                setBackgroundResource(R.drawable.bg_row_ripple)
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    dialog.dismiss()
                    it.onClick()
                }
            }
            if (it.icon != 0) {
                row.addView(ImageView(a).apply {
                    setImageResource(it.icon)
                    setColorFilter(if (it.destructive) 0xFFFF453A.toInt() else 0xFFFFFFFF.toInt())
                }, LinearLayout.LayoutParams(dp(a, 24), dp(a, 24)).apply { marginEnd = dp(a, 14) })
            }
            val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(a).apply {
                text = it.label
                textSize = 16f
                setTextColor(if (it.destructive) 0xFFFF453A.toInt() else Color.WHITE)
            })
            if (it.subtitle != null) {
                texts.addView(TextView(a).apply {
                    text = it.subtitle
                    textSize = 12.5f
                    setTextColor(0x8CFFFFFF.toInt())
                })
            }
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (it.checked) {
                row.addView(TextView(a).apply {
                    text = "✓"
                    textSize = 18f
                    setTextColor(0xFF00E676.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                })
            }
            group.addView(row)
            if (i < items.size - 1) {
                group.addView(View(a).apply { setBackgroundColor(0x1FFFFFFF) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(a, if (it.icon != 0) 54 else 16) })
            }
        }
        root.addView(group)
        root.addView(TextView(a).apply {
            text = "Cancelar"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_segmented)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 50)).apply { topMargin = dp(a, 10) })
        show(a, dialog, root)
    }

    /** Hoja con contenido propio y botones abajo. Devuelve el diálogo para cerrarlo. */
    fun custom(
        a: Activity, title: String?, content: View,
        buttons: List<Pair<String, () -> Unit>>, primary: Int = 0, scroll: Boolean = false,
    ): Dialog {
        val (dialog, root) = base(a, title, null)
        if (scroll) {
            root.addView(ScrollView(a).apply { addView(content) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 320)))
        } else {
            root.addView(content)
        }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(a, 14), 0, 0)
        }
        buttons.forEachIndexed { i, (label, action) ->
            row.addView(TextView(a).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                if (i == primary) {
                    setTextColor(Color.BLACK)
                    setBackgroundResource(R.drawable.bg_live_pill)
                } else {
                    setTextColor(Color.WHITE)
                    setBackgroundResource(R.drawable.bg_segmented)
                }
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }, LinearLayout.LayoutParams(0, dp(a, 48), 1f).apply {
                if (i > 0) marginStart = dp(a, 10)
            })
        }
        root.addView(row)
        show(a, dialog, root)
        return dialog
    }
}
