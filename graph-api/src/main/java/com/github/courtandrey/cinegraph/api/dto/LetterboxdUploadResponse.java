package com.github.courtandrey.cinegraph.api.dto;

import java.util.List;

public record LetterboxdUploadResponse(String hash, List<LetterboxdGraph> graphs) {}
