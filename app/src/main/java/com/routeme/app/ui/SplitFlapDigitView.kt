package com.routeme.app.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.routeme.app.R

/**
 * A single split-flap digit card. Shows one character with a mechanical
 * flip-down animation when the digit changes.
 */
class SplitFlapDigitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val topHalf: TextView
    private val bottomHalf: TextView
    private val divider: android.view.View
    private var currentDigit: Char = '0'

    init {
        setBackgroundResource(R.drawable.bg_split_flap_digit)
        clipChildren = false
        clipToPadding = false

        val digitTextSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics
        )

        topHalf = TextView(context).apply {
            text = "0"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, digitTextSize)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        bottomHalf = TextView(context).apply {
            text = "0"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, digitTextSize)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        divider = android.view.View(context).apply {
            setBackgroundColor(0xFF1A1A1A.toInt())
        }

        addView(topHalf, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(bottomHalf, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val dividerHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics
        ).toInt()
        val dividerParams = LayoutParams(LayoutParams.MATCH_PARENT, dividerHeight).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(divider, dividerParams)
    }

    fun setDigit(digit: Char, animate: Boolean = true) {
        if (digit == currentDigit) return
        if (!animate) {
            currentDigit = digit
            topHalf.text = digit.toString()
            bottomHalf.text = digit.toString()
            return
        }

        val flipOut = ObjectAnimator.ofFloat(topHalf, "rotationX", 0f, -90f).apply {
            duration = 100
            interpolator = AccelerateInterpolator()
        }
        val flipIn = ObjectAnimator.ofFloat(bottomHalf, "rotationX", 90f, 0f).apply {
            duration = 100
            interpolator = DecelerateInterpolator()
        }

        flipOut.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                currentDigit = digit
                topHalf.text = digit.toString()
                bottomHalf.text = digit.toString()
                topHalf.rotationX = 0f
            }
        })

        AnimatorSet().apply {
            playSequentially(flipOut, flipIn)
            start()
        }
    }
}
