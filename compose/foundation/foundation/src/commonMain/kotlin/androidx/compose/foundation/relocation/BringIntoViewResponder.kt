/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.foundation.relocation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.modifier.ModifierLocalProvider
import androidx.compose.ui.modifier.ProvidableModifierLocal
import androidx.compose.ui.platform.debugInspectorInfo
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A parent that can respond to [bringChildIntoView] requests from its children, and scroll so that
 * the item is visible on screen. To apply a responder to an element, pass it to the
 * [bringIntoViewResponder] modifier.
 *
 * When a component calls [BringIntoViewRequester.bringIntoView], the
 * [BringIntoView ModifierLocal][ModifierLocalBringIntoViewParent] is read to gain access to the
 * [BringIntoViewResponder], which is responsible for, in order:
 *
 * 1. Calculating a rectangle that its parent responder should bring into view by returning it from
 *    [calculateRectForParent].
 * 2. Performing any scroll or other layout adjustments needed to ensure the requested rectangle is
 *    brought into view in [bringChildIntoView].
 *
 * Here is a sample defining a custom [BringIntoViewResponder]:
 * @sample androidx.compose.foundation.samples.BringIntoViewResponderSample
 *
 * Here is a sample where a composable is brought into view:
 * @sample androidx.compose.foundation.samples.BringIntoViewSample
 *
 * Here is a sample where a part of a composable is brought into view:
 * @sample androidx.compose.foundation.samples.BringPartOfComposableIntoViewSample
 *
 * @see BringIntoViewRequester
 */
@ExperimentalFoundationApi
interface BringIntoViewResponder {

    /**
     * Return the rectangle in this node that should be brought into view by this node's parent,
     * in coordinates relative to this node. If this node needs to adjust itself to bring
     * [localRect] into view, the returned rectangle should be the destination rectangle that
     * [localRect] will eventually occupy once this node's content is adjusted.
     *
     * @param localRect The rectangle that should be brought into view, relative to this node. This
     * will be the same rectangle passed to [bringChildIntoView].
     * @return The rectangle in this node that should be brought into view itself, relative to this
     * node. If this node needs to scroll to bring [localRect] into view, the returned rectangle
     * should be the destination rectangle that [localRect] will eventually occupy, once the
     * scrolling animation is finished.
     */
    @ExperimentalFoundationApi
    fun calculateRectForParent(localRect: Rect): Rect

    /**
     * Bring this specified rectangle into bounds by making this scrollable parent scroll
     * appropriately.
     *
     * @param localRect The rectangle that should be brought into view, relative to this node. This
     * is the same rectangle that will have been passed to [calculateRectForParent].
     */
    @ExperimentalFoundationApi
    suspend fun bringChildIntoView(localRect: Rect)
}

/**
 * A parent that can respond to [BringIntoViewRequester] requests from its children, and scroll so
 * that the item is visible on screen. See [BringIntoViewResponder] for more details about how
 * this mechanism works.
 *
 * @sample androidx.compose.foundation.samples.BringIntoViewResponderSample
 * @sample androidx.compose.foundation.samples.BringIntoViewSample
 *
 * @see BringIntoViewRequester
 */
@ExperimentalFoundationApi
fun Modifier.bringIntoViewResponder(
    responder: BringIntoViewResponder
): Modifier = composed(debugInspectorInfo {
    name = "bringIntoViewResponder"
    properties["responder"] = responder
}) {
    val defaultParent = rememberDefaultBringIntoViewParent()
    val modifier = remember(defaultParent) {
        BringIntoViewResponderModifier(defaultParent)
    }
    modifier.responder = responder
    return@composed modifier
}

/**
 * A modifier that holds state and modifier implementations for [bringIntoViewResponder]. It has
 * access to the next [BringIntoViewParent] via [BringIntoViewChildModifier] and additionally
 * provides itself as the [BringIntoViewParent] for subsequent modifiers. This class is responsible
 * for recursively propagating requests up the responder chain.
 */
@OptIn(ExperimentalFoundationApi::class)
private class BringIntoViewResponderModifier(
    defaultParent: BringIntoViewParent
) : BringIntoViewChildModifier(defaultParent),
    ModifierLocalProvider<BringIntoViewParent?>,
    BringIntoViewParent {

    lateinit var responder: BringIntoViewResponder

    override val key: ProvidableModifierLocal<BringIntoViewParent?>
        get() = ModifierLocalBringIntoViewParent
    override val value: BringIntoViewParent
        get() = this

    /**
     * Responds to a child's request by first converting [rect] into this node's [LayoutCoordinates]
     * and then, concurrently, calling the [responder] and the [parent] to handle the request.
     */
    override suspend fun bringChildIntoView(rect: Rect, childCoordinates: LayoutCoordinates) {
        val layoutCoordinates = layoutCoordinates ?: return
        if (!childCoordinates.isAttached) return
        val localRect = layoutCoordinates.localRectOf(childCoordinates, rect)
        val parentRect = responder.calculateRectForParent(localRect)

        // For the item to be visible, if needs to be in the viewport of all its ancestors.
        // Note: For now we run both of these concurrently, but in the future we could make this
        // configurable. (The child relocation could be executed before the parent, or parent
        // before the child).
        coroutineScope {
            // Bring the requested Child into this parent's view.
            launch {
                responder.bringChildIntoView(localRect)
            }

            parent.bringChildIntoView(parentRect, layoutCoordinates)
        }
    }
}

/**
 * Translates [rect], specified in [sourceCoordinates], into this [LayoutCoordinates].
 */
private fun LayoutCoordinates.localRectOf(
    sourceCoordinates: LayoutCoordinates,
    rect: Rect
): Rect {
    // Translate the supplied layout coordinates into the coordinate system of this parent.
    val localRect = localBoundingBoxOf(sourceCoordinates, clipBounds = false)

    // Translate the rect to this parent's local coordinates.
    return rect.translate(localRect.topLeft)
}