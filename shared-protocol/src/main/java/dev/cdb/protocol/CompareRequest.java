package dev.cdb.protocol;

public record CompareRequest(String candidateScreenshotId, String baselineScreenshotId, String baselineName,
                             Double pixelThreshold, Double maxDiffRatio) {
}
