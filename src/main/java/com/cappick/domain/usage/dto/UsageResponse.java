package com.cappick.domain.usage.dto;

import java.util.List;

public record UsageResponse(List<UsageCategorySummaryDTO> categories) {
}
