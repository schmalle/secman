export type AwsServerCounts = {
  available: boolean;
  totalAwsServers: number | null;
  overdueAwsServers: number | null;
};

export function formatOverdueEc2Share(kpi: AwsServerCounts | null) {
  if (!kpi?.available || kpi.totalAwsServers == null || kpi.overdueAwsServers == null) {
    return { value: 'Not available', subtitle: '' };
  }
  const { overdueAwsServers: overdue, totalAwsServers: total } = kpi;
  if (total === 0) return { value: 'No current EC2 servers', subtitle: '' };

  const counts = new Intl.NumberFormat();
  const percentage = new Intl.NumberFormat(undefined, { style: 'percent', maximumFractionDigits: 1 });
  return {
    value: percentage.format(overdue / total),
    subtitle: `${counts.format(overdue)} of ${counts.format(total)} current EC2 servers`
  };
}
