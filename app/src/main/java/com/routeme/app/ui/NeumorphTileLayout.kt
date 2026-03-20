package com.routeme.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.routeme.app.R
import com.google.android.material.color.MaterialColors

/**
 * Square-cornered neumorphic tile with dual software-layer shadows.
 * When [isActive] is true, a green border is drawn on top.
 *
 * Must use [LAYER_TYPE_SOFTWARE] because [Paint.setShadowLayer] is
 * ignored under hardware acceleration.
 */
class NeumorphTileLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var isActive: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateShadowPaints()
                invalidate()
            }
        }

    private val shadowRadius: Float
    private val shadowOffset: Float
    private val borderWidth: Float

    private val surfaceColor: Int
    private val darkShadowColor: Int
    private val lightShadowColor: Int
    private val activeBorderColor: Int

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val tileRect = RectF()
    private val borderRect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)

        val density = resources.displayMetrics.density
        shadowRadius = 6f * density
        shadowOffset = 4f * density
        borderWidth = 2f * density

        surfaceColor = ContextCompat.getColor(context, R.color.neumorph_surface)
        darkShadowColor = ContextCompat.getColor(context, R.color.neumorph_shadow_dark)
        lightShadowColor = ContextCompat.getColor(context, R.color.neumorph_shadow_light)
        activeBorderColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.GREEN)

        surfacePaint.apply {
            style = Paint.Style.FILL
            color = surfaceColor
        }

        darkShadowPaint.apply {
            style = Paint.Style.FILL
            color = surfaceColor
            setShadowLayer(shadowRadius, shadowOffset, shadowOffset, darkShadowColor)
        }

        lightShadowPaint.apply {
            style = Paint.Style.FILL
            color = surfaceColor
            setShadowLayer(shadowRadius, -shadowOffset, -shadowOffset, lightShadowColor)
        }

        activeBorderPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            color = activeBorderColor
        }

        updateShadowPaints()

        // Padding so children don't overlap the shadow bleed zone
        val inset = (shadowRadius + shadowOffset).toInt()
        setPadding(
            paddingLeft.coerceAtLeast(inset),
            paddingTop.coerceAtLeast(inset),
            paddingRight.coerceAtLeast(inset),
            paddingBottom.coerceAtLeast(inset)
        )
    }

    private fun updateShadowPaints() {
        if (isActive) {
            // Sunken: dark top-left, light bottom-right
            darkShadowPaint.setShadowLayer(shadowRadius, -shadowOffset, -shadowOffset, darkShadowColor)
            lightShadowPaint.setShadowLayer(shadowRadius, shadowOffset, shadowOffset, lightShadowColor)
        } else {
            // Raised: dark bottom-right, light top-left
            darkShadowPaint.setShadowLayer(shadowRadius, shadowOffset, shadowOffset, darkShadowColor)
            lightShadowPaint.setShadowLayer(shadowRadius, -shadowOffset, -shadowOffset, lightShadowColor)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = shadowRadius + shadowOffset
        tileRect.set(inset, inset, w - inset, h - inset)
        val half = borderWidth / 2f
        borderRect.set(tileRect.left + half, tileRect.top + half, tileRect.right - half, tileRect.bottom - half)
    }

    override fun onDraw(canvas: Canvas) {
        // Dark shadow (bottom-right)
        canvas.drawRect(tileRect, darkShadowPaint)
        // Light shadow (top-left)
        canvas.drawRect(tileRect, lightShadowPaint)
        // Flat surface fill on top
        canvas.drawRect(tileRect, surfacePaint)
        // Active border
        if (isActive) {
            canvas.drawRect(borderRect, activeBorderPaint)
        }
        super.onDraw(canvas)
    }
}
