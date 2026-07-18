/**
 * Global Constants mapping exactly to Backend Enum counterparts.
 * We use `as const` objects + type extraction to satisfy erasableSyntaxOnly
 * while still giving us formal RunStatus.COMPLETED syntax locally.
 */

export const RunStatus = {
  PENDING: 'PENDING',
  QUEUED: 'QUEUED',
  RUNNING: 'RUNNING',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
  APPROVED: 'APPROVED',
  REJECTED: 'REJECTED',
  EXPIRED: 'EXPIRED',
  PAUSED: 'PAUSED',
  PROCESSING: 'PROCESSING',
  CREATED: 'CREATED',
  CANCELLED: 'CANCELLED',
  SUCCESS: 'SUCCESS',
  DRAFT: 'DRAFT'
} as const;

export type RunStatus = typeof RunStatus[keyof typeof RunStatus];

export const RoleType = {
  ADMIN: 'admin',
  USER: 'user'
} as const;

export type RoleType = typeof RoleType[keyof typeof RoleType];

export const MessageRole = {
  USER: 'user',
  ASSISTANT: 'assistant',
  SYSTEM: 'system',
  TOOL: 'tool'
} as const;

export type MessageRole = typeof MessageRole[keyof typeof MessageRole];

export const StreamEventType = {
  START: 'START',
  CONTENT_DELTA: 'CONTENT_DELTA',
  STOP: 'STOP',
  ERROR: 'ERROR',
  RUN_PAUSED: 'RUN_PAUSED',
  RUN_COMPLETED: 'RUN_COMPLETED',
  TOOL_CALL: 'TOOL_CALL',
  TOOL_RESULT: 'TOOL_RESULT'
} as const;

export type StreamEventType = typeof StreamEventType[keyof typeof StreamEventType];

export const GatewayLogStatus = {
  ALLOWED: 'ALLOWED',
  BLOCKED: 'BLOCKED',
  HITL_QUEUED: 'HITL_QUEUED',
} as const;
export type GatewayLogStatus = typeof GatewayLogStatus[keyof typeof GatewayLogStatus];

export const ApprovalAction = {
  APPROVE: 'APPROVE',
  REJECT: 'REJECT',
} as const;
export type ApprovalAction = typeof ApprovalAction[keyof typeof ApprovalAction];

export const FeedbackDirection = {
  THUMBS_UP: 'THUMBS_UP',
  THUMBS_DOWN: 'THUMBS_DOWN',
  NONE: 'NONE',
} as const;
export type FeedbackDirection = typeof FeedbackDirection[keyof typeof FeedbackDirection];

export const RequiredActionType = {
  TOOL_APPROVAL: 'TOOL_APPROVAL',
  SWARM_ESCALATION_APPROVAL: 'SWARM_ESCALATION_APPROVAL',
  TEAM_MEMBER_DISPATCH_APPROVAL: 'TEAM_MEMBER_DISPATCH_APPROVAL',
} as const;
export type RequiredActionType = typeof RequiredActionType[keyof typeof RequiredActionType];
