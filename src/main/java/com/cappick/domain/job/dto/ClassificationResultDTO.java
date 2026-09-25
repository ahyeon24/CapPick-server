package com.cappick.domain.job.dto;

import java.util.List;

// classification 완료 result와 같은 모양(API 명세 §5). Job.resultJson에 이 형태로 저장하고
// GET /v1/jobs/{id} 응답에도 그대로 쓴다.
public record ClassificationResultDTO(List<ClassificationDecisionDTO> decisions) {
}
