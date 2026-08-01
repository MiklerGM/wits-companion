package io.github.miklergm.witscompanion.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * A minimal flow (wrap) layout: children are placed left to right and wrap to the next
 * line when they would overflow the available width. Adapts to the width it is given, so a
 * grid of cards uses more columns on a wide head-unit display and fewer on a narrow one —
 * without pulling in a flexbox dependency.
 *
 * Children keep their own measured width (give them a fixed width for a tidy grid). Margins
 * on child `MarginLayoutParams` are honoured; [hGap]/[vGap] add spacing on top of them.
 */
class FlowLayout(context: Context) : ViewGroup(context) {

    var hGap: Int = 0
    var vGap: Int = 0

    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val available = widthSize - paddingLeft - paddingRight

        var x = 0          // used width on the current line
        var lineHeight = 0 // tallest child on the current line
        var totalHeight = 0
        var maxLineWidth = 0

        forEachVisibleChild { child ->
            val lp = child.layoutParams as MarginLayoutParams
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val w = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val h = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (x > 0 && x + hGap + w > available) {
                // wrap
                totalHeight += lineHeight + vGap
                maxLineWidth = maxOf(maxLineWidth, x)
                x = 0
                lineHeight = 0
            }
            x += (if (x > 0) hGap else 0) + w
            lineHeight = maxOf(lineHeight, h)
        }
        totalHeight += lineHeight
        maxLineWidth = maxOf(maxLineWidth, x)

        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) widthSize
        else maxLineWidth + paddingLeft + paddingRight
        val height = totalHeight + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = (r - l) - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0

        forEachVisibleChild { child ->
            val lp = child.layoutParams as MarginLayoutParams
            val w = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val h = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (x > paddingLeft && x + hGap + w > paddingLeft + available) {
                x = paddingLeft
                y += lineHeight + vGap
                lineHeight = 0
            }
            val cx = x + (if (x > paddingLeft) hGap else 0) + lp.leftMargin
            child.layout(cx, y + lp.topMargin, cx + child.measuredWidth, y + lp.topMargin + child.measuredHeight)
            x += (if (x > paddingLeft) hGap else 0) + w
            lineHeight = maxOf(lineHeight, h)
        }
    }

    private inline fun forEachVisibleChild(action: (View) -> Unit) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != GONE) action(child)
        }
    }
}
