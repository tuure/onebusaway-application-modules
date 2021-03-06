/**
 * Copyright (C) 2011 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.transit_data_federation.impl.realtime.gtfs_realtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.onebusaway.collections.FactoryMap;
import org.onebusaway.collections.MappingLibrary;
import org.onebusaway.collections.Min;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.realtime.api.VehicleLocationRecord;
import org.onebusaway.transit_data_federation.services.blocks.BlockCalendarService;
import org.onebusaway.transit_data_federation.services.blocks.BlockInstance;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockConfigurationEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockStopTimeEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockTripEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.StopTimeEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.TripEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtimeOneBusAway;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.Position;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;

class GtfsRealtimeTripLibrary {

  private static final Logger _log = LoggerFactory.getLogger(GtfsRealtimeTripLibrary.class);

  private GtfsRealtimeEntitySource _entitySource;

  private BlockCalendarService _blockCalendarService;

  /**
   * This is primarily here to assist with unit testing.
   */
  private long _currentTime = 0;

  public void setEntitySource(GtfsRealtimeEntitySource entitySource) {
    _entitySource = entitySource;
  }

  public void setBlockCalendarService(BlockCalendarService blockCalendarService) {
    _blockCalendarService = blockCalendarService;
  }

  public long getCurrentTime() {
    return _currentTime;
  }

  public void setCurrentTime(long currentTime) {
    _currentTime = currentTime;
  }

  public List<CombinedTripUpdatesAndVehiclePosition> groupTripUpdatesAndVehiclePositions(
      FeedMessage tripUpdates, FeedMessage vehiclePositions) {

    Map<BlockDescriptor, List<TripUpdate>> tripUpdatesByBlockDescriptor = getTripUpdatesByBlockDescriptor(tripUpdates);
    boolean tripsIncludeVehicleIds = determineIfTripUpdatesIncludeVehicleIds(tripUpdatesByBlockDescriptor.keySet());
    Map<BlockDescriptor, FeedEntity> vehiclePositionsByBlockDescriptor = getVehiclePositionsByBlockDescriptor(
        vehiclePositions, tripsIncludeVehicleIds);

    List<CombinedTripUpdatesAndVehiclePosition> updates = new ArrayList<CombinedTripUpdatesAndVehiclePosition>(
        tripUpdatesByBlockDescriptor.size());

    for (Map.Entry<BlockDescriptor, List<TripUpdate>> entry : tripUpdatesByBlockDescriptor.entrySet()) {

      CombinedTripUpdatesAndVehiclePosition update = new CombinedTripUpdatesAndVehiclePosition();
      update.block = entry.getKey();
      update.tripUpdates = entry.getValue();

      FeedEntity vehiclePositionEntity = vehiclePositionsByBlockDescriptor.get(update.block);
      if (vehiclePositionEntity != null) {
        VehiclePosition vehiclePosition = vehiclePositionEntity.getVehicle();
        update.vehiclePosition = vehiclePosition;
        if (vehiclePosition.hasVehicle()) {
          VehicleDescriptor vehicle = vehiclePosition.getVehicle();
          if (vehicle.hasId()) {
            update.block.setVehicleId(vehicle.getId());
          }
        }
      }

      if (update.block.getVehicleId() == null) {
        for (TripUpdate tripUpdate : update.tripUpdates) {
          if (tripUpdate.hasVehicle()) {
            VehicleDescriptor vehicle = tripUpdate.getVehicle();
            if (vehicle.hasId()) {
              update.block.setVehicleId(vehicle.getId());
            }
          }
        }
      }

      updates.add(update);
    }

    return updates;
  }

  /**
   * The {@link VehicleLocationRecord} is guarnateed to have a
   * {@link VehicleLocationRecord#getVehicleId()} value.
   * 
   * @param update
   * @return
   */
  public VehicleLocationRecord createVehicleLocationRecordForUpdate(
      CombinedTripUpdatesAndVehiclePosition update) {

    VehicleLocationRecord record = new VehicleLocationRecord();
    record.setTimeOfRecord(currentTime());

    BlockDescriptor blockDescriptor = update.block;

    record.setBlockId(blockDescriptor.getBlockEntry().getId());

    applyTripUpdatesToRecord(blockDescriptor, update.tripUpdates, record);

    if (update.vehiclePosition != null) {
      applyVehiclePositionToRecord(update.vehiclePosition, record);
    }

    /**
     * By default, we use the block id as the vehicle id
     */
    record.setVehicleId(record.getBlockId());

    if (blockDescriptor.getVehicleId() != null) {
      String agencyId = record.getBlockId().getAgencyId();
      record.setVehicleId(new AgencyAndId(agencyId,
          blockDescriptor.getVehicleId()));
    }

    return record;
  }

  /****
   * 
   ****/

  private boolean determineIfTripUpdatesIncludeVehicleIds(
      Collection<BlockDescriptor> blockDescriptors) {

    int vehicleIdCount = 0;
    for (BlockDescriptor blockDescriptor : blockDescriptors) {
      if (blockDescriptor.getVehicleId() != null)
        vehicleIdCount++;
    }

    return vehicleIdCount > blockDescriptors.size() / 2;
  }

  private Map<BlockDescriptor, List<TripUpdate>> getTripUpdatesByBlockDescriptor(
      FeedMessage tripUpdates) {

    Map<BlockDescriptor, List<TripUpdate>> tripUpdatesByBlockDescriptor = new FactoryMap<BlockDescriptor, List<TripUpdate>>(
        new ArrayList<TripUpdate>());

    int totalTrips = 0;
    int unknownTrips = 0;

    for (FeedEntity entity : tripUpdates.getEntityList()) {
      TripUpdate tripUpdate = entity.getTripUpdate();
      if (tripUpdate == null) {
        _log.warn("epxected a FeedEntity with a TripUpdate");
        continue;
      }
      TripDescriptor trip = tripUpdate.getTrip();
      BlockDescriptor blockDescriptor = getTripDescriptorAsBlockDescriptor(
          trip, true);
      totalTrips++;
      if (blockDescriptor == null) {
        unknownTrips++;
        continue;
      }

      if (!hasDelayValue(tripUpdate)) {
        continue;
      }

      tripUpdatesByBlockDescriptor.get(blockDescriptor).add(tripUpdate);
    }

    if (unknownTrips > 0) {
      _log.warn("unknown/total trips= {}/{}", unknownTrips, totalTrips);
    }

    return tripUpdatesByBlockDescriptor;
  }

  private boolean hasDelayValue(TripUpdate tripUpdate) {

    if (tripUpdate.hasExtension(GtfsRealtimeOneBusAway.delay)) {
      return true;
    }

    if (tripUpdate.getStopTimeUpdateCount() == 0)
      return false;

    StopTimeUpdate stopTimeUpdate = tripUpdate.getStopTimeUpdate(0);
    if (!(stopTimeUpdate.hasArrival() || stopTimeUpdate.hasDeparture()))
      return false;

    boolean hasDelay = false;
    if (stopTimeUpdate.hasDeparture()) {
      StopTimeEvent departure = stopTimeUpdate.getDeparture();
      hasDelay |= departure.hasDelay();
    }
    if (stopTimeUpdate.hasArrival()) {
      StopTimeEvent arrival = stopTimeUpdate.getArrival();
      hasDelay |= arrival.hasDelay();
    }
    return hasDelay;
  }

  private Map<BlockDescriptor, FeedEntity> getVehiclePositionsByBlockDescriptor(
      FeedMessage vehiclePositions, boolean includeVehicleIds) {

    Map<BlockDescriptor, FeedEntity> vehiclePositionsByBlockDescriptor = new HashMap<BlockDescriptor, FeedEntity>();

    for (FeedEntity entity : vehiclePositions.getEntityList()) {
      VehiclePosition vehiclePosition = entity.getVehicle();
      if (vehiclePosition == null) {
        _log.warn("epxected a FeedEntity with a VehiclePosition");
        continue;
      }
      if (!(vehiclePosition.hasTrip() || vehiclePosition.hasPosition())) {
        continue;
      }
      TripDescriptor trip = vehiclePosition.getTrip();
      BlockDescriptor blockDescriptor = getTripDescriptorAsBlockDescriptor(
          trip, includeVehicleIds);
      if (blockDescriptor != null) {
        FeedEntity existing = vehiclePositionsByBlockDescriptor.put(
            blockDescriptor, entity);
        if (existing != null) {
          _log.warn("multiple updates found for trip: " + trip);
        }
      }
    }

    return vehiclePositionsByBlockDescriptor;
  }

  private BlockDescriptor getTripDescriptorAsBlockDescriptor(
      TripDescriptor trip, boolean includeVehicleIds) {
    if (!trip.hasTripId() || trip.hasRouteId()) {
      return null;
    }
    TripEntry tripEntry = _entitySource.getTrip(trip.getTripId());
    if (tripEntry == null) {
      _log.warn("no trip found with id=" + trip.getTripId());
      return null;
    }
    BlockEntry block = tripEntry.getBlock();
    BlockDescriptor blockDescriptor = new BlockDescriptor();
    blockDescriptor.setBlockEntry(block);
    if (trip.hasStartDate())
      blockDescriptor.setStartDate(trip.getStartDate());
    if (trip.hasStartTime())
      blockDescriptor.setStartTime(trip.getStartTime());

    return blockDescriptor;
  }

  private void applyTripUpdatesToRecord(BlockDescriptor blockDescriptor,
      List<TripUpdate> tripUpdates, VehicleLocationRecord record) {

    BlockEntry block = blockDescriptor.getBlockEntry();
    long t = currentTime();
    long timeFrom = t - 30 * 60 * 1000;
    long timeTo = t + 30 * 60 * 1000;

    List<BlockInstance> instances = _blockCalendarService.getActiveBlocks(
        block.getId(), timeFrom, timeTo);
    if (instances.isEmpty()) {
      instances = _blockCalendarService.getClosestActiveBlocks(block.getId(), t);
    }
    if (instances.isEmpty()) {
      _log.warn("could not find any active schedules instance for the specified block="
          + block.getId() + " tripUpdates=" + tripUpdates);
      return;
    }

    /**
     * TODO: Eventually, use startDate and startTime to distinguish between
     * different instances
     */
    BlockInstance instance = instances.get(0);
    BlockConfigurationEntry blockConfiguration = instance.getBlock();
    List<BlockTripEntry> blockTrips = blockConfiguration.getTrips();

    Map<String, List<TripUpdate>> tripUpdatesByTripId = MappingLibrary.mapToValueList(
        tripUpdates, "trip.tripId");

    int currentTime = (int) ((t - instance.getServiceDate()) / 1000);
    BestScheduleDeviation best = new BestScheduleDeviation();

    for (BlockTripEntry blockTrip : blockTrips) {
      TripEntry trip = blockTrip.getTrip();
      AgencyAndId tripId = trip.getId();
      List<TripUpdate> updatesForTrip = tripUpdatesByTripId.get(tripId.getId());
      if (updatesForTrip != null) {
        for (TripUpdate tripUpdate : updatesForTrip) {

          if (tripUpdate.hasExtension(GtfsRealtimeOneBusAway.delay)) {
            /**
             * TODO: Improved logic around picking the "best" schedule deviation
             */
            int delay = tripUpdate.getExtension(GtfsRealtimeOneBusAway.delay);
            best.delta = 0;
            best.isInPast = false;
            best.scheduleDeviation = delay;
          }

          if (tripUpdate.hasExtension(GtfsRealtimeOneBusAway.timestamp)) {
            best.timestamp = tripUpdate.getExtension(GtfsRealtimeOneBusAway.timestamp) * 1000;
          }

          for (StopTimeUpdate stopTimeUpdate : tripUpdate.getStopTimeUpdateList()) {
            BlockStopTimeEntry blockStopTime = getBlockStopTimeForStopTimeUpdate(
                tripUpdate, stopTimeUpdate, blockTrip.getStopTimes(),
                instance.getServiceDate());
            if (blockStopTime == null)
              continue;
            StopTimeEntry stopTime = blockStopTime.getStopTime();
            int currentArrivalTime = computeArrivalTime(stopTime,
                stopTimeUpdate, instance.getServiceDate());
            if (currentArrivalTime >= 0) {
              updateBestScheduleDeviation(currentTime,
                  stopTime.getArrivalTime(), currentArrivalTime, best);
            }
            int currentDepartureTime = computeDepartureTime(stopTime,
                stopTimeUpdate, instance.getServiceDate());
            if (currentDepartureTime >= 0) {
              updateBestScheduleDeviation(currentTime,
                  stopTime.getDepartureTime(), currentDepartureTime, best);
            }
          }
        }
      }
    }

    record.setServiceDate(instance.getServiceDate());
    record.setScheduleDeviation(best.scheduleDeviation);
    if (best.timestamp != 0) {
      record.setTimeOfRecord(best.timestamp);
    }
  }

  private BlockStopTimeEntry getBlockStopTimeForStopTimeUpdate(
      TripUpdate tripUpdate, StopTimeUpdate stopTimeUpdate,
      List<BlockStopTimeEntry> stopTimes, long serviceDate) {

    if (stopTimeUpdate.hasStopSequence()) {
      int stopSequence = stopTimeUpdate.getStopSequence();
      if (0 <= stopSequence && stopSequence < stopTimes.size()) {
        BlockStopTimeEntry blockStopTime = stopTimes.get(stopSequence);
        if (!stopTimeUpdate.hasStopId()) {
          return blockStopTime;
        }
        if (blockStopTime.getStopTime().getStop().getId().getId().equals(
            stopTimeUpdate.getStopId())) {
          return blockStopTime;
        }
        // The stop sequence and stop id didn't match, so we fall through to
        // match by stop id if possible

      } else {
        _log.warn("StopTimeSequence is out of bounds: stopSequence="
            + stopSequence + " tripUpdate=\n" + tripUpdate);
      }
    }

    if (stopTimeUpdate.hasStopId()) {
      int time = getTimeForStopTimeUpdate(stopTimeUpdate, serviceDate);
      String stopId = stopTimeUpdate.getStopId();
      // There could be loops, meaning a stop could appear multiple times along
      // a trip. To get around this.
      Min<BlockStopTimeEntry> bestMatches = new Min<BlockStopTimeEntry>();
      for (BlockStopTimeEntry blockStopTime : stopTimes) {
        if (blockStopTime.getStopTime().getStop().getId().getId().equals(stopId)) {
          StopTimeEntry stopTime = blockStopTime.getStopTime();
          int departureDelta = Math.abs(stopTime.getDepartureTime() - time);
          int arrivalDelta = Math.abs(stopTime.getArrivalTime() - time);
          bestMatches.add(departureDelta, blockStopTime);
          bestMatches.add(arrivalDelta, blockStopTime);
        }
      }
      if (!bestMatches.isEmpty())
        return bestMatches.getMinElement();
    }

    return null;
  }

  private int getTimeForStopTimeUpdate(StopTimeUpdate stopTimeUpdate,
      long serviceDate) {
    long t = currentTime();
    if (stopTimeUpdate.hasArrival()) {
      StopTimeEvent arrival = stopTimeUpdate.getArrival();
      if (arrival.hasDelay()) {
        return (int) ((t - serviceDate) / 1000 - arrival.getDelay());
      }
      if (arrival.hasTime())
        return (int) (arrival.getTime() - serviceDate / 1000);
    }
    if (stopTimeUpdate.hasDeparture()) {
      StopTimeEvent departure = stopTimeUpdate.getDeparture();
      if (departure.hasDelay()) {
        return (int) ((t - serviceDate) / 1000 - departure.getDelay());
      }
      if (departure.hasTime())
        return (int) (departure.getTime() - serviceDate / 1000);
    }
    throw new IllegalStateException(
        "expected at least an arrival or departure time or delay for update: "
            + stopTimeUpdate);
  }

  private int computeArrivalTime(StopTimeEntry stopTime,
      StopTimeUpdate stopTimeUpdate, long serviceDate) {
    if (!stopTimeUpdate.hasArrival())
      return -1;
    StopTimeEvent arrival = stopTimeUpdate.getArrival();
    if (arrival.hasDelay())
      return stopTime.getArrivalTime() + arrival.getDelay();
    if (arrival.hasTime())
      return (int) (arrival.getTime() - serviceDate / 1000);
    throw new IllegalStateException(
        "expected arrival delay or time for stopTimeUpdate " + stopTimeUpdate);
  }

  private int computeDepartureTime(StopTimeEntry stopTime,
      StopTimeUpdate stopTimeUpdate, long serviceDate) {
    if (!stopTimeUpdate.hasDeparture())
      return -1;
    StopTimeEvent departure = stopTimeUpdate.getDeparture();
    if (departure.hasDelay())
      return stopTime.getDepartureTime() + departure.getDelay();
    if (departure.hasTime())
      return (int) (departure.getTime() - serviceDate / 1000);
    throw new IllegalStateException(
        "expected departure delay or time for stopTimeUpdate " + stopTimeUpdate);
  }

  private void updateBestScheduleDeviation(int currentTime,
      int expectedStopTime, int actualStopTime, BestScheduleDeviation best) {

    int delta = Math.abs(currentTime - actualStopTime);
    boolean isInPast = currentTime > actualStopTime;
    int scheduleDeviation = actualStopTime - expectedStopTime;

    if (delta < best.delta || (!isInPast && best.isInPast)) {
      best.delta = delta;
      best.isInPast = isInPast;
      best.scheduleDeviation = scheduleDeviation;
    }
  }

  private void applyVehiclePositionToRecord(VehiclePosition vehiclePosition,
      VehicleLocationRecord record) {
    Position position = vehiclePosition.getPosition();
    record.setCurrentLocationLat(position.getLatitude());
    record.setCurrentLocationLon(position.getLongitude());
  }

  private long currentTime() {
    if (_currentTime != 0)
      return _currentTime;
    return System.currentTimeMillis();
  }

  private static class BestScheduleDeviation {
    public int delta = Integer.MAX_VALUE;
    public int scheduleDeviation = 0;
    public boolean isInPast = true;
    public long timestamp = 0;
  }
}
