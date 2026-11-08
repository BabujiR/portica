import { useEffect, useRef } from 'react';
import * as echarts from 'echarts/core';
import { BarChart, LineChart } from 'echarts/charts';
import { AxisPointerComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { ThemeMode } from '../app/ThemeProvider';

// Only what the dashboards use, so the analytics chunk stays small.
echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, AxisPointerComponent, CanvasRenderer]);

export type ChartOption = echarts.EChartsCoreOption;

/** Chart colors per theme: categorical slots, status colors and recessive chrome. */
export function chartPalette(mode: ThemeMode) {
  const dark = mode === 'dark';
  return {
    series: dark ? ['#3987e5', '#d95926', '#199e70', '#c98500'] : ['#2a78d6', '#eb6834', '#1baf7a', '#eda100'],
    good: '#0ca30c',
    warning: '#fab219',
    serious: '#ec835a',
    critical: '#d03b3b',
    text: dark ? '#c3c2b7' : '#52514e',
    muted: '#898781',
    grid: dark ? '#2c2c2a' : '#e1e0d9',
    axis: dark ? '#383835' : '#c3c2b7',
    tooltipBg: dark ? '#262624' : '#ffffff',
    tooltipText: dark ? '#f0efec' : '#0b0b0b',
  };
}

/** Shared axis, grid and tooltip styling. */
export function baseOption(mode: ThemeMode): ChartOption {
  const c = chartPalette(mode);
  return {
    backgroundColor: 'transparent',
    textStyle: { color: c.text, fontFamily: 'inherit' },
    grid: { left: 8, right: 16, top: 32, bottom: 8, containLabel: true },
    tooltip: {
      backgroundColor: c.tooltipBg,
      borderColor: c.axis,
      textStyle: { color: c.tooltipText },
      confine: true,
    },
    legend: { top: 0, left: 0, icon: 'roundRect', itemWidth: 12, itemHeight: 4, textStyle: { color: c.text } },
  };
}

export function axisStyle(mode: ThemeMode) {
  const c = chartPalette(mode);
  return {
    axisLine: { lineStyle: { color: c.axis } },
    axisTick: { show: false },
    axisLabel: { color: c.muted },
    splitLine: { lineStyle: { color: c.grid } },
  };
}

/** Renders an ECharts option; resizes with its container and disposes on unmount. */
export function EChart({ option, height = 260, ariaLabel }: { option: ChartOption; height?: number; ariaLabel: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const chart = useRef<echarts.EChartsType | null>(null);

  useEffect(() => {
    if (!ref.current) return undefined;
    const instance = echarts.init(ref.current, undefined, { renderer: 'canvas' });
    chart.current = instance;
    const observer = new ResizeObserver(() => instance.resize());
    observer.observe(ref.current);
    return () => {
      observer.disconnect();
      instance.dispose();
      chart.current = null;
    };
  }, []);

  useEffect(() => {
    chart.current?.setOption(option, { notMerge: true });
  }, [option]);

  return <div ref={ref} role="img" aria-label={ariaLabel} style={{ width: '100%', height }} />;
}
