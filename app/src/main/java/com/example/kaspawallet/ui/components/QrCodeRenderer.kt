package com.example.kaspawallet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.security.MessageDigest
import kotlin.math.abs

@Composable
fun KaspaQrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    qrColor: Color = Color(0xFF091114),
    backgroundColor: Color = Color.White
) {
    // Generate deterministic 21x21 QR-like matrix based on data hash and standard finder patterns
    val matrix = remember(content) {
        generateQrMatrix(content, 25)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridCount = matrix.size
            val cellSize = this.size.width / gridCount

            for (row in 0 until gridCount) {
                for (col in 0 until gridCount) {
                    if (matrix[row][col]) {
                        drawRect(
                            color = qrColor,
                            topLeft = Offset(col * cellSize, row * cellSize),
                            size = Size(cellSize, cellSize)
                        )
                    }
                }
            }
        }
    }
}

private fun generateQrMatrix(data: String, dimension: Int = 25): Array<BooleanArray> {
    val matrix = Array(dimension) { BooleanArray(dimension) { false } }

    // Finder pattern helper (top-left, top-right, bottom-left)
    fun drawFinder(startX: Int, startY: Int) {
        for (r in 0..6) {
            for (c in 0..6) {
                val isOuter = r == 0 || r == 6 || c == 0 || c == 6
                val isInner = r in 2..4 && c in 2..4
                matrix[startY + r][startX + c] = isOuter || isInner
            }
        }
    }

    drawFinder(0, 0)
    drawFinder(dimension - 7, 0)
    drawFinder(0, dimension - 7)

    // Timing patterns
    for (i in 7 until dimension - 7) {
        matrix[6][i] = i % 2 == 0
        matrix[i][6] = i % 2 == 0
    }

    // Deterministic payload encoding using SHA-256 hash
    val md = MessageDigest.getInstance("SHA-256")
    val hash = md.digest(data.toByteArray())

    var hashIdx = 0
    for (r in 0 until dimension) {
        for (c in 0 until dimension) {
            // Skip finder patterns and timing lines
            val inTopLeft = r < 8 && c < 8
            val inTopRight = r < 8 && c >= dimension - 8
            val inBottomLeft = r >= dimension - 8 && c < 8
            val inTiming = r == 6 || c == 6

            if (!inTopLeft && !inTopRight && !inBottomLeft && !inTiming) {
                val byteVal = hash[hashIdx % hash.size].toInt()
                val bitVal = (byteVal shr ((r * dimension + c) % 8)) and 1
                val pseudoEntropy = (data.hashCode() + r * 37 + c * 17) % 7
                matrix[r][c] = (bitVal xor (pseudoEntropy % 2)) == 1
                hashIdx++
            }
        }
    }

    return matrix
}
