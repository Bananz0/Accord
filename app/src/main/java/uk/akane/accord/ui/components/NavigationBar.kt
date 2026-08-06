package uk.akane.accord.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.data.jellyfin.JellyfinUserImage
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.logic.inverseLerp
import uk.akane.accord.logic.isDarkMode
import uk.akane.accord.logic.sp
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.player.FloatingPanelLayout
import uk.akane.accord.ui.components.player.PlayerPopupMenu
import uk.akane.cupertino.utils.AnimationUtils
import uk.akane.cupertino.popup.PopupHelper
import uk.akane.cupertino.popup.showPopupMenuFromAnchorRect

class NavigationBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : ViewGroup(context, attrs, defStyleAttr, defStyleRes),
    DefaultLifecycleObserver {

    private val activity: MainActivity
        get() = context as MainActivity

    private val accentColor = resources.getColor(R.color.accentColor, null)

    private val bottomDividerColor = resources.getColor(R.color.navigationBarDivider, null)
    private val bottomDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bottomDividerColor }

    private val avatarColor = accentColor
    private val avatarDrawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_person_navigation_bar, null)!!

    /**
     * The signed-in user's Jellyfin picture, drawn in place of the glyph once it arrives. Null until
     * then, and whenever the user has no picture set.
     */
    private var avatarBitmap: Bitmap? = null
    private var avatarRequest: Disposable? = null
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val avatarClipPath = Path()
    private var avatarJob: Job? = null

    private val ellipsisBackgroundColor = resources.getColor(R.color.navigationBarEllipsisBackground, null)
    private val ellipsisBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ellipsisBackgroundColor }

    private val ellipsisColor = accentColor
    private val ellipsisDrawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_ellipsis_navigation_bar, null)!!

    private val addDrawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_plus, null)!!

    private val chevronColor = accentColor
    private val chevronDrawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_chevron_left, null)!!

    private val expandedNavigationBarBackgroundColor = resources.getColor(R.color.navigationBarExpandedBackground, null)

    private val blurAppendColor = resources.getColor(R.color.navigationBarBlurAppendColor, null)
    private val blurAppendColorDark = resources.getColor(R.color.navigationBarBlurAppendDarkModeColor, null)

    private var titleText = ""
    private var returnButtonText = ""
    private var shouldDrawExpandedTitle = true
    private var useTransparentExpandedBackground = false
    private var expandedAccentColor = accentColor
    var shouldDrawAddButton: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var lifecycle: Lifecycle? = null

    init {
        context.withStyledAttributes(attrs, R.styleable.NavigationBar, 0, 0) {
            titleText = getString(R.styleable.NavigationBar_title) ?: ""
            returnButtonText = getString(R.styleable.NavigationBar_returnButtonText) ?: ""
            shouldDrawLargeMenuItem = getBoolean(R.styleable.NavigationBar_hasLargeMenuItems, true)
            shouldDrawAddButton = getBoolean(R.styleable.NavigationBar_hasAddButton, false)
            shouldDrawReturnButton = getBoolean(R.styleable.NavigationBar_hasReturnButton, false)
            shouldDrawExpandedTitle = getBoolean(R.styleable.NavigationBar_hasExpandedTitle, true)
            useTransparentExpandedBackground = getBoolean(
                R.styleable.NavigationBar_transparentExpandedBackground,
                false
            )
            expandedAccentColor = getColor(
                R.styleable.NavigationBar_expandedAccentColor,
                accentColor
            )
            Log.d("TAG", "should: $shouldDrawLargeMenuItem")
        }
        setWillNotDraw(false)
        isClickable = true
    }

    private var collapseProgress = 0F
    private var renderShowProgress = 0F
    private var scrollOffsetPx = 0
    private var collapseStartOffsetPx = 0

    var blurRadius: Float = 0F
        set(value) {
            field = value
            renderNode?.setRenderEffect(
                RenderEffect.createBlurEffect(
                    value,
                    value,
                    Shader.TileMode.MIRROR
                )
            )
        }

    var shouldDrawLargeMenuItem: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var shouldDrawReturnButton: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    fun setTitle(text: String) {
        if (titleText == text) return
        titleText = text
        invalidate()
    }

    fun setCollapseStartOffsetPx(offsetPx: Int) {
        val clamped = offsetPx.coerceAtLeast(0)
        if (collapseStartOffsetPx == clamped) return
        collapseStartOffsetPx = clamped
        handleScroll(scrollOffsetPx)
    }

    private var renderNode: RenderNode? = null

    private val expandedTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.navigationBarTitle, null)
        textSize = 34.sp.px
        textAlign = Paint.Align.LEFT
        typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
    }
    private val expandedTitleFontMetrics by lazy { expandedTitlePaint.fontMetrics }
    private val collapsedTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.navigationBarTitle, null)
        textSize = 18.sp.px
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
    }

    private var appendHeight = 0
    private var childTopMargin = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)

        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        measureChildren(widthMeasureSpec, heightMeasureSpec)

        val child = getChildAt(0)
        if (child != null) {
            val lp = child.layoutParams as MarginLayoutParams
            childTopMargin = lp.topMargin
            val childWidth = widthSize - paddingLeft - paddingRight - lp.leftMargin - lp.rightMargin
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(child.measuredHeight, MeasureSpec.EXACTLY)
            )
            appendHeight = child.measuredHeight + lp.bottomMargin + lp.topMargin
        }

        val desiredHeight = EXPANDED_STATE_HEIGHT.dp.px.toInt() +
                calculateExpandedHeightPadding().toInt() +
                (if (shouldDrawReturnButton) EXPANDED_PADDED_HEIGHT_RETURN else 0).dp.px.toInt() +
                paddingTop + paddingBottom +
                appendHeight

        val measuredHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> desiredHeight.coerceAtMost(heightSize)
            MeasureSpec.UNSPECIFIED -> desiredHeight
            else -> desiredHeight
        }

        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> widthSize
            MeasureSpec.UNSPECIFIED -> widthSize
            else -> widthSize
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        if (renderShowProgress < 1F) {
            drawExpandedBackground(canvas)
        }

        val layer = canvas.saveLayerAlpha(
            0F,
            0F,
            width.toFloat(),
            height.toFloat(),
            ((1f - collapseProgress) * 255).toInt()
        )

        drawExpandedTitle(canvas)

        Log.d("TAG", "invalidated! $shouldDrawLargeMenuItem")
        menuButtonBounds.setEmpty()
        addButtonBounds.setEmpty()
        avatarBounds.setEmpty()
        if (shouldDrawLargeMenuItem) {
            drawMenuItems(canvas)
        }

        canvas.restoreToCount(layer)

        if (renderShowProgress > 0F) {

            val layerRender = canvas.saveLayerAlpha(
                0F,
                0F,
                width.toFloat(),
                height.toFloat(),
                (renderShowProgress * 255).toInt()
            )

            canvas.withTranslation(0F, -translationY) {
                renderNode?.let {
                    canvas.drawRenderNode(it)
                }
            }

            drawRenderNodeOverlay(canvas)
            drawBottomDivider(canvas)

            canvas.restoreToCount(layerRender)

        }

        drawCollapsedTitle(canvas)

        if (shouldDrawReturnButton) {
            canvas.withTranslation(y = -translationY) {
                drawReturnButton(canvas)
            }
        }
    }

    private fun drawRenderNode() {
        renderNode?.beginRecording(renderNodeWidth, renderNodeHeight)?.apply {
            withSave {
                drawColor(expandedNavigationBarBackgroundColor)
                targetView?.draw(this)
            }
        }
        renderNode?.endRecording()
    }

    private fun drawRenderNodeOverlay(canvas: Canvas) {
        if (context.isDarkMode()) {
            canvas.drawColor(blurAppendColor, BlendMode.OVERLAY)
            canvas.drawColor(blurAppendColorDark)
        } else {
            canvas.drawColor(blurAppendColor, BlendMode.HARD_LIGHT)
        }
    }

    private fun drawExpandedBackground(canvas: Canvas) {
        val color = if (useTransparentExpandedBackground) {
            val alpha = (collapseProgress * 255).toInt().coerceIn(0, 255)
            ColorUtils.setAlphaComponent(expandedNavigationBarBackgroundColor, alpha)
        } else {
            expandedNavigationBarBackgroundColor
        }
        if (Color.alpha(color) == 0) return
        canvas.drawColor(color)
    }

    private fun drawBottomDivider(canvas: Canvas) {
        canvas.drawRect(
            0F,
            height - DIVIDER_SIZE.dp.px,
            width.toFloat(),
            height.toFloat(),
            bottomDividerPaint
        )
    }

    private fun drawExpandedTitle(canvas: Canvas) {
        if (!shouldDrawExpandedTitle) return
        if (titleText.isEmpty()) return
        val topY = paddingTop + calculateExpandedHeightPadding() + 3f.dp.px + (if (shouldDrawReturnButton) EXPANDED_PADDED_HEIGHT_RETURN else 0).dp.px.toInt()

        val baseline = topY - expandedTitleFontMetrics.ascent

        canvas.drawText(
            titleText,
            EXPANDED_SIDE_PADDING.dp.px,
            baseline,
            expandedTitlePaint
        )
    }

    private val returnTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = chevronColor
        textSize = 18.sp.px
        textAlign = Paint.Align.LEFT
        typeface = ResourcesCompat.getFont(context, R.font.inter_regular)
        isSubpixelText = true
    }

    private fun drawCollapsedTitle(canvas: Canvas) {
        val paint = collapsedTitlePaint
        val text = titleText

        val x = width / 2f

        val fm = paint.fontMetrics

        val collapseExpandDiff = (EXPANDED_STATE_HEIGHT - COLLAPSED_STATE_HEIGHT).dp.px
        val returnButtonTopTranslation = (if (shouldDrawReturnButton) (EXPANDED_PADDED_HEIGHT_RETURN) else 0).dp.px.toInt()

        val collapsedTop = paddingTop + calculateExpandedHeightPadding() + collapseExpandDiff + returnButtonTopTranslation
        val collapsedBottom = collapsedTop + COLLAPSED_STATE_HEIGHT.dp.px + childTopMargin
        val centerY = (collapsedTop + collapsedBottom) / 2f + COLLAPSED_TITLE_Y_OFFSET.dp.px

        val baseline = centerY - (fm.ascent + fm.descent) / 2f

        paint.alpha = (255 * collapseProgress).toInt()

        canvas.drawText(text, x, baseline, paint)

        /*

         */
    }

    private fun drawReturnButton(canvas: Canvas) {
        val accent = resolveAccentColor()
        val chevronWidth = chevronDrawable.intrinsicWidth
        val chevronHeight = (chevronWidth * (chevronDrawable.intrinsicHeight.toFloat() /
                chevronDrawable.intrinsicWidth.toFloat())).toInt()

        val centerY = paddingTop + (EXPANDED_PADDED_HEIGHT_RETURN.dp.px / 2f) + returnRowYOffset

        val chevronLeft = EXPANDED_PADDED_HEIGHT_RETURN_START_PADDING.dp.px.toInt()
        val chevronTop = (centerY - chevronHeight / 2f).toInt()

        chevronDrawable.setBounds(
            chevronLeft,
            chevronTop,
            chevronLeft + chevronWidth,
            chevronTop + chevronHeight
        )
        chevronDrawable.setTint(accent)
        chevronDrawable.draw(canvas)

        val fm = returnTextPaint.fontMetrics
        val baseline = centerY - (fm.ascent + fm.descent) / 2f
        val textX = chevronLeft + chevronWidth + EXPANDED_PADDED_HEIGHT_RETURN_START_PADDING.dp.px
        returnTextPaint.color = accent
        canvas.drawText(returnButtonText, textX, baseline, returnTextPaint)
    }

    private fun drawMenuItems(canvas: Canvas) {
        val accent = resolveAccentColor()
        val size = EXPANDED_MENU_ITEM_SIZE.dp.px
        val iconSize = 18.dp.px

        val textCenterY = if (shouldDrawReturnButton) {
            paddingTop + (EXPANDED_PADDED_HEIGHT_RETURN.dp.px / 2f) + returnRowYOffset
        } else {
            val topY = paddingTop + calculateExpandedHeightPadding() + 3f.dp.px
            val baseline = topY - expandedTitleFontMetrics.ascent
            baseline + (expandedTitleFontMetrics.ascent + expandedTitleFontMetrics.descent) / 2f
        }

        val drawableLeft = width - EXPANDED_SIDE_PADDING.dp.px - size
        val drawableTop = textCenterY - size / 2f
        val drawableRight = drawableLeft + size
        val drawableBottom = drawableTop + size

        val shouldDrawAvatar = !shouldDrawReturnButton
        val ellipsisLeft = if (shouldDrawAvatar) {
            drawableLeft - size - EXPANDED_ELLIPSIS_MARGIN.dp.px
        } else {
            drawableLeft
        }
        val addLeft = if (shouldDrawAddButton) {
            ellipsisLeft - size - EXPANDED_ELLIPSIS_MARGIN.dp.px
        } else {
            0f
        }
        val ellipsisTop = drawableTop
        val ellipsisRight = ellipsisLeft + size
        val ellipsisBottom = ellipsisTop + size

        val ellipsisCenterX = (ellipsisLeft + ellipsisRight) / 2f
        val ellipsisCenterY = (ellipsisTop + ellipsisBottom) / 2f

        val ellipsisDrawableLeft = (ellipsisCenterX - iconSize / 2f).toInt()
        val ellipsisDrawableTop = (ellipsisCenterY - iconSize / 2f).toInt()
        val ellipsisDrawableRight = ellipsisDrawableLeft + iconSize
        val ellipsisDrawableBottom = ellipsisDrawableTop + iconSize

        ellipsisDrawable.setBounds(
            ellipsisDrawableLeft,
            ellipsisDrawableTop,
            ellipsisDrawableRight.toInt(),
            ellipsisDrawableBottom.toInt()
        )

        if (shouldDrawAvatar) {
            avatarDrawable.setBounds(
                drawableLeft.toInt(),
                drawableTop.toInt(),
                drawableRight.toInt(),
                drawableBottom.toInt()
            )
            // Upstream draws the avatar and nothing else - it has no bounds recorded and no way to
            // be tapped. Recording them here is what lets it open anything.
            avatarBounds.set(drawableLeft, drawableTop, drawableRight, drawableBottom)

            val picture = avatarBitmap
            if (picture != null) {
                // Clipped to a circle rather than drawn square: Jellyfin accepts any aspect ratio,
                // and the glyph this replaces is round, so a raw photo would be the one square thing
                // in the bar.
                val saved = canvas.save()
                avatarClipPath.reset()
                avatarClipPath.addOval(avatarBounds, Path.Direction.CW)
                canvas.clipPath(avatarClipPath)
                // centerCrop: fill the circle from the shorter edge and let the longer one overflow,
                // so a portrait photo is not squashed into a square.
                val scale = maxOf(
                    avatarBounds.width() / picture.width,
                    avatarBounds.height() / picture.height
                )
                val drawWidth = picture.width * scale
                val drawHeight = picture.height * scale
                canvas.drawBitmap(
                    picture,
                    null,
                    RectF(
                        avatarBounds.centerX() - drawWidth / 2f,
                        avatarBounds.centerY() - drawHeight / 2f,
                        avatarBounds.centerX() + drawWidth / 2f,
                        avatarBounds.centerY() + drawHeight / 2f,
                    ),
                    avatarPaint
                )
                canvas.restoreToCount(saved)
            } else {
                avatarDrawable.setTint(avatarColor)
                if (avatarColor != accent) {
                    avatarDrawable.setTint(accent)
                }
                avatarDrawable.draw(canvas)
            }
        }

        if (shouldDrawAddButton) {
            val addRight = addLeft + size
            val addBottom = ellipsisBottom
            val addCenterX = (addLeft + addRight) / 2f
            val addCenterY = (ellipsisTop + addBottom) / 2f
            val addDrawableLeft = (addCenterX - iconSize / 2f).toInt()
            val addDrawableTop = (addCenterY - iconSize / 2f).toInt()
            val addDrawableRight = addDrawableLeft + iconSize
            val addDrawableBottom = addDrawableTop + iconSize

            val backgroundAlpha = (Color.alpha(ellipsisBackgroundColor) *
                (1F - menuButtonTransformFactor * 0.35F)).toInt()
            ellipsisBackgroundPaint.color =
                ColorUtils.setAlphaComponent(ellipsisBackgroundColor, backgroundAlpha)
            canvas.drawRoundRect(
                addLeft,
                ellipsisTop,
                addRight,
                addBottom,
                size / 2f,
                size / 2f,
                ellipsisBackgroundPaint
            )

            addDrawable.setBounds(
                addDrawableLeft,
                addDrawableTop,
                addDrawableRight.toInt(),
                addDrawableBottom.toInt()
            )
            addDrawable.setTint(accent)
            addDrawable.draw(canvas)

            addButtonBounds.set(
                addLeft,
                ellipsisTop,
                addRight,
                addBottom
            )
        }

        val backgroundAlpha = (Color.alpha(ellipsisBackgroundColor) *
            (1F - menuButtonTransformFactor * 0.35F)).toInt()
        ellipsisBackgroundPaint.color =
            ColorUtils.setAlphaComponent(ellipsisBackgroundColor, backgroundAlpha)
        canvas.drawRoundRect(
            ellipsisLeft,
            ellipsisTop,
            ellipsisRight,
            ellipsisBottom,
            size / 2f,
            size / 2f,
            ellipsisBackgroundPaint
        )

        ellipsisDrawable.setTint(accent)
        ellipsisDrawable.alpha = (255 * (1F - menuButtonTransformFactor * 0.25F)).toInt()
        ellipsisDrawable.draw(canvas)

        menuButtonBounds.set(
            ellipsisLeft,
            ellipsisTop,
            ellipsisRight,
            ellipsisBottom
        )
    }

    private var renderNodeWidth = 0
    private var renderNodeHeight = 0

    private var targetView: View? = null
    private var avatarClickListener: (() -> Unit)? = null
    private var avatarPressed = false
    private var returnClickListener: (() -> Unit)? = null
    private var menuClickListener: (() -> Unit)? = null
    private var addClickListener: (() -> Unit)? = null
    private var returnButtonPressed = false
    private var menuButtonPressed = false
    private var addButtonPressed = false
    private val returnButtonBounds = RectF()
    private val menuButtonBounds = RectF()
    private val addButtonBounds = RectF()
    private val avatarBounds = RectF()
    private val returnRowYOffset = RETURN_ROW_Y_OFFSET.dp.px
    private var menuButtonChecked = false
    private var menuButtonTransformFactor = 0F
    private var menuButtonAnimator: ValueAnimator? = null

    fun setOnReturnClickListener(listener: (() -> Unit)?) {
        returnClickListener = listener
    }

    /** The profile control, which upstream draws but never wires to anything. */
    fun setOnAvatarClickListener(listener: (() -> Unit)?) {
        avatarClickListener = listener
    }

    fun setOnMenuClickListener(listener: (() -> Unit)?) {
        menuClickListener = listener
    }

    fun setOnAddClickListener(listener: (() -> Unit)?) {
        addClickListener = listener
    }

    fun attach(
        view: RecyclerView,
        applyTopPadding: Boolean = true,
        applyBottomPadding: Boolean = true
    ) {
        targetView = view
        renderNode = RenderNode("BlurredTarget")
        blurRadius = BLUR_STRENGTH.dp.px

        doOnLayout {
            lifecycle = findViewTreeLifecycleOwner()?.lifecycle
            lifecycle?.addObserver(this)

            view.clipToPadding = false
            view.setPadding(
                view.paddingLeft,
                view.paddingTop + (if (applyTopPadding) height else 0),
                view.paddingEnd,
                if (applyBottomPadding) activity.bottomHeight else view.paddingBottom
            )

            drawRenderNode()

            val scrollListener = object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    scrollOffsetPx = (scrollOffsetPx + dy).coerceAtLeast(0)
                    computeRecyclerTopOffset(recyclerView)?.let { scrollOffsetPx = it }
                    handleScroll(scrollOffsetPx)
                }
            }
            view.addOnScrollListener(scrollListener)

            if (view.computeVerticalScrollOffset() == height) {
                view.scrollBy(0, -height)
            }

            renderNodeWidth = width.takeIf { it > 0 } ?: view.width
            renderNodeHeight = (paddingTop + COLLAPSED_STATE_HEIGHT.dp.px + appendHeight).takeIf { it > 0 }?.toInt() ?: view.height

            renderNode?.setPosition(0, 0, renderNodeWidth, renderNodeHeight)

            syncRecyclerScroll(view)
        }
    }

    fun attach(
        view: NestedScrollView,
        applyTopPadding: Boolean = true,
        applyBottomPadding: Boolean = true
    ) {
        targetView = view
        renderNode = RenderNode("BlurredTarget")
        blurRadius = BLUR_STRENGTH.dp.px

        doOnLayout {
            lifecycle = findViewTreeLifecycleOwner()?.lifecycle
            lifecycle?.addObserver(this)

            view.clipToPadding = false
            view.setPadding(
                view.paddingLeft,
                view.paddingTop + (if (applyTopPadding) height else 0),
                view.paddingRight,
                if (applyBottomPadding) activity.bottomHeight else view.paddingBottom
            )

            drawRenderNode()

            view.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                handleScroll(scrollY)
            }

            if (view.scrollY == height) {
                view.scrollBy(0, -height)
            }

            renderNodeWidth = width.takeIf { it > 0 } ?: view.width
            renderNodeHeight = (paddingTop + COLLAPSED_STATE_HEIGHT.dp.px + appendHeight)
                .takeIf { it > 0 }?.toInt() ?: view.height

            renderNode?.setPosition(0, 0, renderNodeWidth, renderNodeHeight)

            val currentOffset = view.scrollY
            scrollOffsetPx = currentOffset
            handleScroll(currentOffset)
        }
    }

    private fun handleScroll(offsetPx: Int) {
        var shouldInvalidate = false
        val isAtTop = targetView?.canScrollVertically(-1) == false
        scrollOffsetPx = if (isAtTop) 0 else offsetPx.coerceAtLeast(0)
        val offset = (scrollOffsetPx - collapseStartOffsetPx).coerceAtLeast(0)
        val maxOffset = height - paddingTop - COLLAPSED_STATE_HEIGHT.dp.px - appendHeight

        val dstTranslationY = (-offset.toFloat()).coerceAtLeast(-maxOffset)
        val newCollapseProgress = if (maxOffset > 0) {
            (offset / maxOffset).coerceIn(0f, 1f)
        } else {
            0f
        }

        val secondStageOffsetEnd = EXPANDED_STATE_HEIGHT.dp.px +
            calculateExpandedHeightPadding() +
            (if (shouldDrawReturnButton) EXPANDED_PADDED_HEIGHT_RETURN else 0).dp.px.toInt() -
            COLLAPSED_STATE_HEIGHT.dp.px + paddingTop + COLLAPSED_STATE_HEIGHT.dp.px + appendHeight * 2
        val secondStageProgress = inverseLerp(
            -maxOffset,
            -secondStageOffsetEnd,
            -offset.toFloat()
        ).coerceIn(0F, 1F)

        drawRenderNode()

        if (dstTranslationY != translationY) {
            shouldInvalidate = true
            translationY = dstTranslationY
        }

        if (newCollapseProgress != collapseProgress) {
            shouldInvalidate = true
            collapseProgress = newCollapseProgress
        }

        if (secondStageProgress != renderShowProgress) {
            shouldInvalidate = true
            renderShowProgress = secondStageProgress
        }

        if (shouldInvalidate) {
            invalidate()
        }
    }

    private fun resolveAccentColor(): Int {
        if (expandedAccentColor == accentColor) return accentColor
        return ColorUtils.blendARGB(expandedAccentColor, accentColor, collapseProgress)
    }

    private fun updateReturnButtonBounds(): RectF {
        val chevronWidth = chevronDrawable.intrinsicWidth
        val chevronHeight = (chevronWidth * (chevronDrawable.intrinsicHeight.toFloat() /
                chevronDrawable.intrinsicWidth.toFloat())).toInt()

        val translationYOffset = -translationY
        val centerY = paddingTop + (EXPANDED_PADDED_HEIGHT_RETURN.dp.px / 2f) +
            returnRowYOffset + translationYOffset
        val chevronLeft = EXPANDED_PADDED_HEIGHT_RETURN_START_PADDING.dp.px
        val chevronTop = centerY - chevronHeight / 2f
        val textX = chevronLeft + chevronWidth + EXPANDED_PADDED_HEIGHT_RETURN_START_PADDING.dp.px
        val textWidth = returnTextPaint.measureText(returnButtonText)
        val hitPadding = 8.dp.px

        returnButtonBounds.set(
            (chevronLeft - hitPadding).coerceAtLeast(0f),
            (chevronTop - hitPadding).coerceAtLeast(0f),
            (textX + textWidth + hitPadding).coerceAtMost(width.toFloat()),
            (chevronTop + chevronHeight + hitPadding).coerceAtMost(height.toFloat())
        )
        return returnButtonBounds
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (shouldDrawLargeMenuItem && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (addButtonBounds.contains(ev.x, ev.y)) {
                addButtonPressed = true
                return true
            }
            if (shouldDrawAvatar() && avatarBounds.contains(ev.x, ev.y)) {
                return true
            }
            if (menuButtonBounds.contains(ev.x, ev.y)) {
                menuButtonPressed = true
                return true
            }
        }
        if (!shouldDrawReturnButton) return super.onInterceptTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
            updateReturnButtonBounds().contains(ev.x, ev.y)
        ) {
            returnButtonPressed = true
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (shouldDrawAvatar() && avatarBounds.contains(event.x, event.y)) {
                    avatarPressed = true
                    return true
                }
                if (shouldDrawLargeMenuItem) {
                    addButtonPressed = addButtonBounds.contains(event.x, event.y)
                    if (addButtonPressed) {
                        return true
                    }
                    menuButtonPressed = menuButtonBounds.contains(event.x, event.y)
                    if (menuButtonPressed) {
                        return true
                    }
                }
                if (!shouldDrawReturnButton) return super.onTouchEvent(event)
                returnButtonPressed = updateReturnButtonBounds().contains(event.x, event.y)
                return returnButtonPressed
            }
            MotionEvent.ACTION_UP -> {
                if (avatarPressed && avatarBounds.contains(event.x, event.y)) {
                    (avatarClickListener ?: { openSettings() }).invoke()
                    performClick()
                }
                avatarPressed = false
                if (addButtonPressed && addButtonBounds.contains(event.x, event.y)) {
                    (addClickListener ?: {}).invoke()
                    performClick()
                }
                addButtonPressed = false
                if (menuButtonPressed && menuButtonBounds.contains(event.x, event.y)) {
                    (menuClickListener ?: { showDefaultMenu() }).invoke()
                    performClick()
                }
                menuButtonPressed = false
                if (!shouldDrawReturnButton) return true
                if (returnButtonPressed && updateReturnButtonBounds().contains(event.x, event.y)) {
                    returnClickListener?.invoke()
                    performClick()
                }
                returnButtonPressed = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                returnButtonPressed = false
                menuButtonPressed = false
                addButtonPressed = false
                avatarPressed = false
                return false
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (childCount != 1) return

        val child = getChildAt(0)
        val lp = child.layoutParams as? MarginLayoutParams

        val marginStart = lp?.marginStart ?: 0
        val marginEnd = lp?.marginEnd ?: 0
        val marginTop = lp?.topMargin ?: 0

        val childLeft = paddingLeft + marginStart
        val childRight = width - paddingRight - marginEnd
        val childTop = (paddingTop + EXPANDED_STATE_HEIGHT.dp.px + calculateExpandedHeightPadding() +
            (if (shouldDrawReturnButton) EXPANDED_PADDED_HEIGHT_RETURN else 0).dp.px.toInt()
            ).toInt() + marginTop + COLLAPSED_CHILD_TOP_PADDING.dp.px.toInt()
        val childBottom = childTop + child.measuredHeight

        child.layout(childLeft, childTop, childRight, childBottom)
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    override fun checkLayoutParams(p: LayoutParams?): Boolean {
        return p is MarginLayoutParams
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        refreshRenderNode()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observeAvatar()
    }

    /**
     * Follows the signed-in user's picture for as long as this bar is on screen.
     *
     * Every screen has its own bar, so this is per-view rather than set once from the activity - and
     * changing the picture in settings then reaches all of them without any of them being told.
     */
    private fun observeAvatar() {
        val owner = findViewTreeLifecycleOwner() ?: return
        avatarJob?.cancel()
        avatarJob = owner.lifecycleScope.launch {
            JellyfinUserImage.urlFlow.collectLatest { url ->
                avatarRequest?.dispose()
                avatarRequest = null
                if (url == null) {
                    avatarBitmap = null
                    invalidate()
                    return@collectLatest
                }
                // Sized to the bar, not to the file. Jellyfin hands back the original - a picture
                // uploaded from a desktop can be several megabytes - and decoding that at full size
                // to draw it at 32dp would be most of a screen's memory for one glyph.
                val target = EXPANDED_MENU_ITEM_SIZE.dp.px.toInt().coerceAtLeast(1)
                avatarRequest = context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(target)
                        .allowHardware(false)
                        .target(
                            onSuccess = { image ->
                                avatarBitmap = image.toBitmap()
                                invalidate()
                            },
                            onError = {
                                avatarBitmap = null
                                invalidate()
                            }
                        )
                        .build()
                )
            }
        }
    }

    override fun onDetachedFromWindow() {
        avatarJob?.cancel()
        avatarJob = null
        avatarRequest?.dispose()
        avatarRequest = null
        menuButtonAnimator?.cancel()
        menuButtonAnimator = null
        menuButtonChecked = false
        menuButtonPressed = false
        returnButtonPressed = false
        returnClickListener = null
        menuClickListener = null
        avatarClickListener = null
        menuEntries = null
        menuEntryClickListener = null
        avatarPressed = false
        dismissOwnPopup()
        super.onDetachedFromWindow()
    }

    /**
     * The popup is drawn by the floating panel, not by this view, so navigating away left it on
     * screen over whatever came next. Closed whenever the bar that opened it goes away or is hidden.
     */
    private fun dismissOwnPopup() {
        if (!menuButtonChecked) return
        (activity.findViewById<FloatingPanelLayout>(R.id.floating))?.dismissPopupMenu()
        menuButtonChecked = false
    }

    fun onVisibilityChangedFromFragment(isHidden: Boolean) {
        if (isHidden) dismissOwnPopup()
        if (!isHidden) {
            refreshRenderNode()
            post { syncScrollWithTarget() }
        }
    }

    private fun syncScrollWithTarget() {
        val view = targetView ?: return
        when (view) {
            is RecyclerView -> syncRecyclerScroll(view)
            is NestedScrollView -> handleScroll(view.scrollY)
        }
    }

    private fun syncRecyclerScroll(recyclerView: RecyclerView) {
        val offset = computeRecyclerScrollOffset(recyclerView)
        if (offset == scrollOffsetPx) return
        scrollOffsetPx = offset
        handleScroll(offset)
    }

    private fun computeRecyclerScrollOffset(recyclerView: RecyclerView): Int {
        if (!recyclerView.canScrollVertically(-1)) return 0
        computeRecyclerTopOffset(recyclerView)?.let { return it }
        return recyclerView.computeVerticalScrollOffset().coerceAtLeast(0)
    }

    private fun computeRecyclerTopOffset(recyclerView: RecyclerView): Int? {
        val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            ?: return null
        if (layoutManager.findFirstVisibleItemPosition() != 0) return null
        val firstChild = layoutManager.findViewByPosition(0) ?: return 0
        val decoratedTop = layoutManager.getDecoratedTop(firstChild)
        return (recyclerView.paddingTop - decoratedTop).coerceAtLeast(0)
    }

    private fun refreshRenderNode() {
        if (collapseProgress > 0F) {
            post {
                drawRenderNode()
                invalidate()
            }
        }
    }

    /**
     * The avatar is drawn on every screen that has no return button, so it has to do something
     * on every one of them. Settings is where an account and its preferences live; a screen may
     * still override it with [setOnAvatarClickListener].
     */
    /** The avatar occupies the slot the return button would otherwise take. */
    private fun shouldDrawAvatar() = !shouldDrawReturnButton

    private fun openSettings() {
        activity.openSettings()
    }

    /** Entries for this screen's overflow menu, or null to use the general one. */
    private var menuEntries: (() -> PopupHelper.PopupEntries)? = null
    private var menuEntryClickListener: ((PopupHelper.PopupEntry) -> Unit)? = null

    /** Lets a screen put its own entries behind the three dots. */
    fun setMenuEntries(
        entries: () -> PopupHelper.PopupEntries,
        onClick: (PopupHelper.PopupEntry) -> Unit,
    ) {
        menuEntries = entries
        menuEntryClickListener = onClick
    }

    private fun showDefaultMenu() {
        if (menuButtonBounds.isEmpty) return
        val popupHost = activity.findViewById<FloatingPanelLayout>(R.id.floating)
        val backgroundView = activity.findViewById<View>(R.id.shrink_container)
        val entries = menuEntries?.invoke() ?: ScreenPopupMenu.build(resources)
        val onEntry: (PopupHelper.PopupEntry) -> Unit = menuEntryClickListener
            ?: { entry -> ScreenPopupMenu.handle(activity, entry) }
        popupHost.showPopupMenuFromAnchorRect(
            entries = entries,
            anchorView = this,
            anchorRect = menuButtonBounds,
            showBelow = true,
            alignToRight = true,
            anchorOffsetY = 12.dp.px.toInt(),
            belowGapPx = 8.dp.px.toInt(),
            backgroundView = backgroundView,
            onDismiss = { setMenuButtonChecked(false) },
            onEntryClick = onEntry,
        )
        setMenuButtonChecked(true)
    }

    private fun setMenuButtonChecked(checked: Boolean) {
        if (menuButtonChecked == checked) return
        menuButtonChecked = checked
        animateMenuButtonChecked(checked)
    }

    private fun animateMenuButtonChecked(checked: Boolean) {
        menuButtonAnimator?.cancel()
        menuButtonAnimator = ValueAnimator.ofFloat(
            if (checked) 0F else 1F,
            if (checked) 1F else 0F
        ).apply {
            duration = 300L
            interpolator = AnimationUtils.easingStandardInterpolator
            addUpdateListener {
                menuButtonTransformFactor = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun calculateExpandedHeightPadding() =
        EXPANDED_PADDED_HEIGHT.dp.px + EXPANDED_PADDED_HEIGHT_APPEND_RETURN.dp.px

    companion object {
        const val EXPANDED_PADDED_HEIGHT = 22
        const val EXPANDED_PADDED_HEIGHT_APPEND_RETURN = -10
        const val EXPANDED_PADDED_HEIGHT_RETURN = 44
        const val EXPANDED_PADDED_HEIGHT_RETURN_START_PADDING = 10
        const val EXPANDED_STATE_HEIGHT = 52
        const val EXPANDED_SIDE_PADDING = 22

        const val EXPANDED_MENU_ITEM_SIZE = 30
        const val EXPANDED_ELLIPSIS_MARGIN = 18
        const val RETURN_ROW_Y_OFFSET = 8

        const val COLLAPSED_STATE_HEIGHT = 44
        const val COLLAPSED_TITLE_Y_OFFSET = 8
        const val COLLAPSED_CHILD_TOP_PADDING = 8
        const val DIVIDER_SIZE = 0.5F
        const val BLUR_STRENGTH = 50F
    }
}
