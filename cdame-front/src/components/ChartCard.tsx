import ReactECharts from 'echarts-for-react';
import { cn } from '../utils/cn';

export interface ChartConfig {
  type: 'radar' | 'pie' | 'bar' | 'line';
  title: string;
  labels: string[];
  datasets: {
    label: string;
    data: number[];
  }[];
}

interface ChartCardProps {
  config: ChartConfig;
  className?: string;
}

export function ChartCard({ config, className }: ChartCardProps) {
  const getOption = () => {
    switch (config.type) {
      case 'radar':
        return {
          title: {
            text: config.title,
            left: 'center',
            textStyle: { fontSize: 14, fontWeight: 'bold' }
          },
          tooltip: {},
          radar: {
            indicator: config.labels.map(label => ({ name: label, max: 100 })),
            shape: 'circle',
            splitNumber: 5,
            axisName: {
              color: '#64748b',
              fontWeight: 'bold'
            },
            splitLine: {
              lineStyle: {
                color: [
                  'rgba(203, 213, 225, 0.4)',
                  'rgba(203, 213, 225, 0.6)',
                  'rgba(203, 213, 225, 0.8)',
                  'rgba(203, 213, 225, 1)',
                ].reverse(),
              },
            },
            splitArea: { show: false },
            axisLine: { lineStyle: { color: 'rgba(203, 213, 225, 0.5)' } },
          },
          series: [
            {
              name: config.datasets[0].label,
              type: 'radar',
              data: [
                {
                  value: config.datasets[0].data,
                  name: config.datasets[0].label,
                  areaStyle: {
                    color: 'rgba(59, 130, 246, 0.4)',
                  },
                  lineStyle: {
                    color: '#3b82f6',
                    width: 2,
                  },
                  itemStyle: {
                    color: '#3b82f6',
                  },
                },
              ],
            },
          ],
        };
      case 'pie':
        return {
          title: {
            text: config.title,
            left: 'center',
            textStyle: { fontSize: 14, fontWeight: 'bold' }
          },
          tooltip: {
            trigger: 'item',
            formatter: '{a} <br/>{b} : {c} ({d}%)'
          },
          legend: {
            orient: 'vertical',
            left: 'left',
            top: 'middle',
            textStyle: { fontSize: 10 }
          },
          series: [
            {
              name: config.datasets[0].label,
              type: 'pie',
              radius: ['40%', '70%'],
              avoidLabelOverlap: false,
              itemStyle: {
                borderRadius: 10,
                borderColor: '#fff',
                borderWidth: 2
              },
              label: {
                show: false,
                position: 'center'
              },
              emphasis: {
                label: {
                  show: true,
                  fontSize: 16,
                  fontWeight: 'bold'
                }
              },
              labelLine: {
                show: false
              },
              data: config.labels.map((label, index) => ({
                value: config.datasets[0].data[index],
                name: label
              }))
            }
          ]
        };
      case 'bar':
        return {
          title: {
            text: config.title,
            left: 'center'
          },
          tooltip: {
            trigger: 'axis'
          },
          xAxis: {
            type: 'category',
            data: config.labels
          },
          yAxis: {
            type: 'value'
          },
          series: [
            {
              name: config.datasets[0].label,
              data: config.datasets[0].data,
              type: 'bar',
              itemStyle: {
                color: '#3b82f6',
                borderRadius: [4, 4, 0, 0]
              }
            }
          ]
        };
      case 'line':
        return {
          title: {
            text: config.title,
            left: 'center'
          },
          tooltip: {
            trigger: 'axis'
          },
          xAxis: {
            type: 'category',
            data: config.labels
          },
          yAxis: {
            type: 'value'
          },
          series: [
            {
              name: config.datasets[0].label,
              data: config.datasets[0].data,
              type: 'line',
              smooth: true,
              itemStyle: {
                color: '#3b82f6'
              },
              areaStyle: {
                color: 'rgba(59, 130, 246, 0.2)'
              }
            }
          ]
        };
      default:
        return {};
    }
  };

  return (
    <div className={cn("bg-white p-4 rounded-3xl border border-slate-100 shadow-sm mt-4 overflow-hidden", className)}>
      <ReactECharts 
        option={getOption()} 
        style={{ height: '300px', width: '100%' }} 
        opts={{ renderer: 'svg' }}
      />
    </div>
  );
}
