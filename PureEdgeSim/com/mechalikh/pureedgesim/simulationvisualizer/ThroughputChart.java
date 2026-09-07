/**
 *     PureEdgeSim:  A Simulation Framework for Performance Evaluation of Cloud, Edge and Mist Computing Environments 
 *
 *     This file is part of PureEdgeSim Project.
 *
 *     PureEdgeSim is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     PureEdgeSim is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with PureEdgeSim. If not, see <http://www.gnu.org/licenses/>.
 *     
 *     @author CottonBuds (Thesis extension for Google Cluster Trace v3 replay)
 **/
package com.mechalikh.pureedgesim.simulationvisualizer;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.knowm.xchart.XYSeries.XYSeriesRenderStyle;
import org.knowm.xchart.style.markers.SeriesMarkers;

import com.mechalikh.pureedgesim.simulationmanager.ResearchSimLog;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;

/**
 * Real-time Swing GUI chart displaying Throughput (tasks/min) over simulation time.
 */
public class ThroughputChart extends Chart {

	protected List<Double> throughputList = new ArrayList<>();
	protected List<Double> currentTime = new ArrayList<>();

	public ThroughputChart(String title, String xAxisTitle, String yAxisTitle, SimulationManager simulationManager) {
		super(title, xAxisTitle, yAxisTitle, simulationManager);
		getChart().getStyler().setDefaultSeriesRenderStyle(XYSeriesRenderStyle.Line);
		updateSize(0.0, null, 0.0, null);
	}

	@Override
	public void update() {
		double time = simulationManager.getSimulation().clock();
		int currentSec = (int) time;
		if (currentSec != clock) {
			clock = currentSec;
			double tput = 0.0;
			if (simulationManager.getSimulationLogger() instanceof ResearchSimLog) {
				ResearchSimLog resLog = (ResearchSimLog) simulationManager.getSimulationLogger();
				int completed = resLog.getTasksSucceeded();
				double minutes = (time > 0) ? time / 60.0 : 1.0;
				tput = completed / minutes;
			} else {
				int completed = simulationManager.getSimulationLogger().getTasksSent()
						- simulationManager.getSimulationLogger().getTasksFailed();
				double minutes = (time > 0) ? time / 60.0 : 1.0;
				tput = (completed > 0) ? completed / minutes : 0.0;
			}

			currentTime.add(time);
			throughputList.add(tput);

			updateSeries(getChart(), "Throughput", toArray(currentTime), toArray(throughputList),
					SeriesMarkers.NONE, new Color(0, 102, 204));
		}
	}
}
