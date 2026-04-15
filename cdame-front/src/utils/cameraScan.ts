const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value))

export type CameraGuideRect = {
  x: number
  y: number
  width: number
  height: number
}

export const DOCUMENT_GUIDE_RECT: CameraGuideRect = {
  x: 0.12,
  y: 0.08,
  width: 0.76,
  height: 0.84
}

type CaptureEnhancedCameraOptions = {
  mimeType?: string
  quality?: number
  guideRect?: CameraGuideRect
}

export type CameraFrameQualityResult = {
  passed: boolean
  score: number
  issues: string[]
  coverage: number
  sharpness: number
  highlightRatio: number
  tiltDeg: number
}

const normalizeGuideRect = (guideRect?: CameraGuideRect): CameraGuideRect => {
  if (!guideRect) {
    return DOCUMENT_GUIDE_RECT
  }
  const width = clamp(guideRect.width, 0.2, 1)
  const height = clamp(guideRect.height, 0.2, 1)
  const x = clamp(guideRect.x, 0, 1 - width)
  const y = clamp(guideRect.y, 0, 1 - height)
  return { x, y, width, height }
}

const guideRectToPixels = (guideRect: CameraGuideRect, width: number, height: number) => {
  const x = Math.round(guideRect.x * width)
  const y = Math.round(guideRect.y * height)
  const guideWidth = Math.round(guideRect.width * width)
  const guideHeight = Math.round(guideRect.height * height)
  return {
    x: clamp(x, 0, Math.max(0, width - 1)),
    y: clamp(y, 0, Math.max(0, height - 1)),
    width: clamp(guideWidth, 1, Math.max(1, width - x)),
    height: clamp(guideHeight, 1, Math.max(1, height - y))
  }
}

const computeBorderLumaMean = (data: Uint8ClampedArray, width: number, height: number) => {
  const border = Math.max(4, Math.floor(Math.min(width, height) * 0.06))
  let sum = 0
  let count = 0
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (x < border || y < border || x >= width - border || y >= height - border) {
        const idx = (y * width + x) * 4
        const luma = data[idx] * 0.299 + data[idx + 1] * 0.587 + data[idx + 2] * 0.114
        sum += luma
        count++
      }
    }
  }
  return count > 0 ? sum / count : 128
}

const detectAutoCropRect = (data: Uint8ClampedArray, width: number, height: number) => {
  const block = Math.max(8, Math.floor(Math.min(width, height) / 120))
  const borderMean = computeBorderLumaMean(data, width, height)
  let minX = width
  let minY = height
  let maxX = -1
  let maxY = -1

  for (let by = 0; by < height; by += block) {
    const yEnd = Math.min(height, by + block)
    for (let bx = 0; bx < width; bx += block) {
      const xEnd = Math.min(width, bx + block)
      let sum = 0
      let sumSq = 0
      let count = 0
      for (let y = by; y < yEnd; y++) {
        for (let x = bx; x < xEnd; x++) {
          const idx = (y * width + x) * 4
          const luma = data[idx] * 0.299 + data[idx + 1] * 0.587 + data[idx + 2] * 0.114
          sum += luma
          sumSq += luma * luma
          count++
        }
      }
      if (count === 0) continue
      const mean = sum / count
      const variance = sumSq / count - mean * mean
      const isDocumentLike = variance > 160 || Math.abs(mean - borderMean) > 24
      if (isDocumentLike) {
        minX = Math.min(minX, bx)
        minY = Math.min(minY, by)
        maxX = Math.max(maxX, xEnd)
        maxY = Math.max(maxY, yEnd)
      }
    }
  }

  if (maxX < 0 || maxY < 0) {
    return { x: 0, y: 0, width, height }
  }

  const margin = block * 2
  minX = clamp(minX - margin, 0, width - 1)
  minY = clamp(minY - margin, 0, height - 1)
  maxX = clamp(maxX + margin, minX + 1, width)
  maxY = clamp(maxY + margin, minY + 1, height)

  const cropWidth = maxX - minX
  const cropHeight = maxY - minY
  const cropArea = cropWidth * cropHeight
  const fullArea = width * height

  if (cropArea < fullArea * 0.2) {
    return { x: 0, y: 0, width, height }
  }

  return { x: minX, y: minY, width: cropWidth, height: cropHeight }
}

const extractGuideAndCropRect = (
  sourceCtx: CanvasRenderingContext2D,
  sourceWidth: number,
  sourceHeight: number,
  guideRect?: CameraGuideRect
) => {
  const normalizedGuideRect = normalizeGuideRect(guideRect)
  const guidePixelRect = guideRectToPixels(normalizedGuideRect, sourceWidth, sourceHeight)
  const guideImage = sourceCtx.getImageData(
    guidePixelRect.x,
    guidePixelRect.y,
    guidePixelRect.width,
    guidePixelRect.height
  )
  const autoCropRect = detectAutoCropRect(guideImage.data, guidePixelRect.width, guidePixelRect.height)
  const cropRect = {
    x: guidePixelRect.x + autoCropRect.x,
    y: guidePixelRect.y + autoCropRect.y,
    width: autoCropRect.width,
    height: autoCropRect.height
  }
  return { guidePixelRect, cropRect }
}

const normalizeEdgeAngle = (angle: number) => {
  let value = angle % 180
  if (value < 0) value += 180
  return value
}

export function evaluateCameraFrameQuality(
  video: HTMLVideoElement,
  guideRect?: CameraGuideRect
): CameraFrameQualityResult {
  if (!video.videoWidth || !video.videoHeight) {
    throw new Error('尚未获取到有效画面')
  }

  const sourceCanvas = document.createElement('canvas')
  sourceCanvas.width = video.videoWidth
  sourceCanvas.height = video.videoHeight
  const sourceCtx = sourceCanvas.getContext('2d')
  if (!sourceCtx) {
    throw new Error('无法初始化图像质量检测上下文')
  }
  sourceCtx.drawImage(video, 0, 0, sourceCanvas.width, sourceCanvas.height)

  const { guidePixelRect, cropRect } = extractGuideAndCropRect(
    sourceCtx,
    sourceCanvas.width,
    sourceCanvas.height,
    guideRect
  )
  const cropImage = sourceCtx.getImageData(cropRect.x, cropRect.y, cropRect.width, cropRect.height)
  const { data, width, height } = cropImage
  const totalPixels = width * height
  const guideArea = guidePixelRect.width * guidePixelRect.height
  const coverage = clamp((width * height) / Math.max(1, guideArea), 0, 1)

  let highlightPixels = 0
  const gray = new Float32Array(totalPixels)
  for (let i = 0; i < totalPixels; i++) {
    const idx = i * 4
    const luma = data[idx] * 0.299 + data[idx + 1] * 0.587 + data[idx + 2] * 0.114
    gray[i] = luma
    if (luma >= 245) {
      highlightPixels++
    }
  }
  const highlightRatio = highlightPixels / Math.max(1, totalPixels)

  let laplaceSum = 0
  let laplaceSumSq = 0
  let laplaceCount = 0
  const angleBins = new Uint32Array(180)

  for (let y = 1; y < height - 1; y++) {
    for (let x = 1; x < width - 1; x++) {
      const idx = y * width + x
      const center = gray[idx]
      const left = gray[idx - 1]
      const right = gray[idx + 1]
      const top = gray[idx - width]
      const bottom = gray[idx + width]
      const laplace = 4 * center - left - right - top - bottom
      laplaceSum += laplace
      laplaceSumSq += laplace * laplace
      laplaceCount++

      const gx = right - left
      const gy = bottom - top
      const magnitude = Math.sqrt(gx * gx + gy * gy)
      if (magnitude > 24) {
        const tangentAngle = normalizeEdgeAngle((Math.atan2(gy, gx) * 180) / Math.PI + 90)
        angleBins[Math.round(tangentAngle) % 180]++
      }
    }
  }

  const laplaceMean = laplaceSum / Math.max(1, laplaceCount)
  const laplaceVariance = Math.max(0, laplaceSumSq / Math.max(1, laplaceCount) - laplaceMean * laplaceMean)
  const sharpness = Math.min(1, laplaceVariance / 700)

  let dominantBin = 0
  let dominantCount = 0
  for (let i = 0; i < angleBins.length; i++) {
    if (angleBins[i] > dominantCount) {
      dominantCount = angleBins[i]
      dominantBin = i
    }
  }

  const nearestAxisDelta = Math.min(
    Math.abs(dominantBin - 0),
    Math.abs(dominantBin - 90),
    Math.abs(dominantBin - 179)
  )
  const tiltDeg = nearestAxisDelta
  const tiltPenalty = clamp((tiltDeg - 6) / 16, 0, 1)
  const glarePenalty = clamp((highlightRatio - 0.015) / 0.09, 0, 1)
  const coveragePenalty = clamp((0.72 - coverage) / 0.52, 0, 1)
  const sharpnessPenalty = clamp((0.56 - sharpness) / 0.56, 0, 1)
  const scoreFloat =
    (1 - sharpnessPenalty) * 42 +
    (1 - glarePenalty) * 24 +
    (1 - coveragePenalty) * 24 +
    (1 - tiltPenalty) * 10
  const score = clamp(Math.round(scoreFloat), 0, 100)

  const issues: string[] = []
  if (coverage < 0.42) issues.push('文件未放满取景框')
  if (sharpness < 0.28) issues.push('画面模糊，请保持稳定并重新对焦')
  if (highlightRatio > 0.07) issues.push('反光较强，请调整光线或角度')
  if (tiltDeg > 14) issues.push('拍摄角度倾斜，请尽量正对文件')
  const passed = score >= 62 && issues.length < 3

  return {
    passed,
    score,
    issues,
    coverage: Number(coverage.toFixed(3)),
    sharpness: Number(sharpness.toFixed(3)),
    highlightRatio: Number(highlightRatio.toFixed(3)),
    tiltDeg: Number(tiltDeg.toFixed(1))
  }
}

export async function captureEnhancedCameraFrame(
  video: HTMLVideoElement,
  canvas: HTMLCanvasElement,
  options?: CaptureEnhancedCameraOptions
) {
  const mimeType = options?.mimeType || 'image/png'
  const quality = options?.quality ?? 0.98
  if (!video.videoWidth || !video.videoHeight) {
    throw new Error('尚未获取到有效画面')
  }

  const sourceCanvas = document.createElement('canvas')
  sourceCanvas.width = video.videoWidth
  sourceCanvas.height = video.videoHeight
  const sourceCtx = sourceCanvas.getContext('2d')
  if (!sourceCtx) {
    throw new Error('无法初始化图像处理上下文')
  }
  sourceCtx.drawImage(video, 0, 0, sourceCanvas.width, sourceCanvas.height)
  const { cropRect } = extractGuideAndCropRect(sourceCtx, sourceCanvas.width, sourceCanvas.height, options?.guideRect)

  canvas.width = cropRect.width
  canvas.height = cropRect.height
  const targetCtx = canvas.getContext('2d')
  if (!targetCtx) {
    throw new Error('无法初始化目标画布')
  }

  targetCtx.drawImage(
    sourceCanvas,
    cropRect.x,
    cropRect.y,
    cropRect.width,
    cropRect.height,
    0,
    0,
    cropRect.width,
    cropRect.height
  )

  const blob = await new Promise<Blob | null>((resolve) => {
    canvas.toBlob(resolve, mimeType, quality)
  })
  if (!blob) {
    throw new Error('无法生成扫描图像')
  }
  return blob
}
