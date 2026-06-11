/**
 * DTO for review action (approve/reject).
 */
export interface ReviewExceptionRequestDto {
  reviewComment?: string;
}

export function createReviewExceptionRequestDto(comment?: string): ReviewExceptionRequestDto {
  return comment ? { reviewComment: comment } : {};
}
