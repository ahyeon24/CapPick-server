package com.cappick.domain.usage;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cappick.common.AuthorizationHeaders;
import com.cappick.domain.installation.Installation;
import com.cappick.domain.installation.InstallationService;
import com.cappick.domain.usage.dto.UsageCategorySummaryDTO;
import com.cappick.domain.usage.dto.UsageResponse;

// API 명세 §7 GET /v1/usage. 조회뿐이라 Idempotency-Key는 필요 없다.
@RestController
@RequestMapping("/v1/usage")
public class UsageController {

    private final UsageService usageService;
    private final InstallationService installationService;

    public UsageController(UsageService usageService, InstallationService installationService) {
        this.usageService = usageService;
        this.installationService = installationService;
    }

    @GetMapping
    public UsageResponse getUsage(@RequestHeader("Authorization") String authorization) {
        String credential = AuthorizationHeaders.extractBearerToken(authorization);
        Installation installation = installationService.resolveByCredential(credential);

        List<UsageCategorySummaryDTO> categories = usageService.usageSummary(installation).stream()
            .map(summary -> {
                // 설치별 한도가 있는 카테고리만 limit_units를 채운다. resets_at은 주기적으로
                // 초기화되는 한도가 아니라서 null이다.
                Integer limit = usageService.installationQuotaLimit(summary.category()).orElse(null);
                boolean canSubmit = limit == null || summary.usedUnits() + summary.reservedUnits() < limit;
                return new UsageCategorySummaryDTO(
                    summary.category(), summary.usedUnits(), summary.reservedUnits(), limit, null, canSubmit);
            })
            .toList();
        return new UsageResponse(categories);
    }
}
