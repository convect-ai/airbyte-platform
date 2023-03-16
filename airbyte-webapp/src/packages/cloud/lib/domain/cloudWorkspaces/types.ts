import { ConnectionStatus, ConnectionScheduleType, ConnectionScheduleTimeUnit } from "core/request/AirbyteClient";

export enum CreditStatus {
  "POSITIVE" = "positive",
  "NEGATIVE_WITHIN_GRACE_PERIOD" = "negative_within_grace_period",
  "NEGATIVE_BEYOND_GRACE_PERIOD" = "negative_beyond_grace_period",
  "NEGATIVE_MAX_THRESHOLD" = "negative_max_threshold",
}

export interface CloudWorkspace {
  name: string;
  workspaceId: string;
  creatorUserId: string;
  remainingCredits: number;
  creditStatus?: CreditStatus;
  lastCreditPurchaseIncrementTimestamp?: number | null;
  trialExpiryTimestamp?: number | null;
}

export interface CreditConsumptionByConnector {
  connectionId: string;
  connectionName: string;
  status: ConnectionStatus;
  creditsConsumed: number;
  destinationConnectionName: string;
  destinationDefinitionId: string;
  destinationDefinitionName: string;
  destinationId: string;
  sourceConnectionName: string;
  sourceDefinitionId: string;
  sourceDefinitionName: string;
  sourceId: string;
  connectionScheduleType: ConnectionScheduleType | null;
  connectionScheduleTimeUnit: ConnectionScheduleTimeUnit | null;
  connectionScheduleUnits: number | null;
}

export interface ConsumptionPerConnectionPerTimeframe {
  timeframe: string;
  billedCost: number;
  freeUsage: number;
  connection: {
    connectionId: string;
    connectionName: string;
    status: ConnectionStatus;
    creditsConsumed: number | null;
    destinationConnectionName: string;
    destinationDefinitionId: string;
    destinationDefinitionName: string;
    destinationIcon: string;
    destinationId: string;
    sourceConnectionName: string;
    sourceDefinitionId: string;
    sourceDefinitionName: string;
    sourceIcon: string;
    sourceId: string;
    connectionScheduleType: ConnectionScheduleType | null;
    connectionScheduleTimeUnit: ConnectionScheduleTimeUnit | null;
    connectionScheduleUnits: number | null;
  };
}

export interface CloudWorkspaceUsage {
  workspaceId: string;
  creditConsumptionByConnector: CreditConsumptionByConnector[];
  creditConsumptionByDay: Array<{
    date: [number, number, number];
    creditsConsumed: number;
  }>;
  consumptionPerConnectionPerTimeframe: ConsumptionPerConnectionPerTimeframe[];
}
