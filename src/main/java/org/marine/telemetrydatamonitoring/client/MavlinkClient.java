package org.marine.telemetrydatamonitoring.client;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.ardupilotmega.Wind;
import io.dronefleet.mavlink.common.*;
import org.marine.telemetrydatamonitoring.service.TelemetryService;
import org.springframework.stereotype.Component;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
@Component
public class MavlinkClient implements Runnable {
    private final TelemetryService telemetryService;

    private final String missionPlannerHost = "localhost";
    private final int missionPlannerPort = 14550;
    private final int udpPort = 14557;
    private final int udpPort2 = 14558;
    private  int count ;
    private final LinkedHashMap<String, Object> telemetryData = new LinkedHashMap<>();
    private final List<Map<String, Object>> waypoints = new ArrayList<>();  // Store waypoints
    private Double prevLat = null, prevLon = null;
    private double totalDistance = 0.0;
    private double homeLat = 35.0766971;
    private double homeLon = 43.79;
    private boolean isAirborne = false;
    private double startTimeSeconds;

    public MavlinkClient(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
        initializeTelemetryData();
    }

    private void initializeTelemetryData() {
        telemetryData.put("sysid", null);
        telemetryData.put("alt", null);
        telemetryData.put("dist_traveled", null);
        telemetryData.put("wp_dist", null);
        telemetryData.put("dist_to_home", 0.0);
        telemetryData.put("vertical_speed", 0.0);
        telemetryData.put("groundspeed", 0.0);
        telemetryData.put("wind_vel", 0.0);
        telemetryData.put("airspeed", 0.0);
        telemetryData.put("roll", 0.0);
        telemetryData.put("pitch", 0.0);
        telemetryData.put("yaw", 0.0);
        telemetryData.put("time_in_air", 0.0);
        telemetryData.put("time_to_air_min_sec", 0.0);
        telemetryData.put("gps_hdop", 0.0);
        telemetryData.put("toh", null);
        telemetryData.put("tot", null);
        telemetryData.put("battery_voltage", 0.0);
        telemetryData.put("battery_current", 0.00);
        telemetryData.put("ch3percent", null);
        telemetryData.put("ch3out", null);
        telemetryData.put("ch9out", 0.00);
        telemetryData.put("ch10out", 0.00);
        telemetryData.put("ch11out", 0.00);
        telemetryData.put("ch12out", 0.00);
        telemetryData.put("home", 0.00);
        telemetryData.put("waypoints_count", 0);
        telemetryData.put("waypoints", new ArrayList<String>());
    }

    public void startListening() {
        new Thread(this).start();
    }

    @Override
    public void run() {
        startTimeSeconds = System.currentTimeMillis() / 1000.0;
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::emitTelemetry, 1, 1, TimeUnit.SECONDS);

        new Thread(this::startUdpListener).start();
        new Thread(this::startUdpListener2).start();
        new Thread(this::startTcpListener).start();
    }
    private void sendMissionRequestInt(MavlinkConnection connection, int seq) {

        try {
            System.out.println("Requesting Mission Item: " + seq); // Debugging

            MissionRequestInt missionRequestInt = MissionRequestInt.builder()
                    .targetSystem(1)
                    .targetComponent(1)
                    .seq(seq)
                    .build();

            connection.send2(255, 0, missionRequestInt);
            System.out.println("MissionRequestInt sent for sequence: " + seq);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    private void startUdpListener() {
        listenForUdpMessages(udpPort);
    }

    private void startUdpListener2() {
        listenForUdpMessages(udpPort2);
    }

    private void listenForUdpMessages(int port) {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (true) {
                socket.receive(packet);
                try (InputStream inputStream = new ByteArrayInputStream(packet.getData(), packet.getOffset(), packet.getLength())) {
                    MavlinkConnection connection = MavlinkConnection.create(inputStream, null);
                    MavlinkMessage<?> message = connection.next();
                    if (message != null) {
                        processTelemetryMessage(message);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private MavlinkConnection mavlinkConnection;  // Store connection globally
    private void startTcpListener() {
        try (Socket socket = new Socket(missionPlannerHost, missionPlannerPort);
             InputStream inputStream = socket.getInputStream();
             OutputStream outputStream = socket.getOutputStream()) {

            MavlinkConnection connection = MavlinkConnection.create(inputStream, outputStream);

            // Send a mission request for item index 0 (requesting first mission item)
            for (int i=0; i< 14; i++) {
                sendMissionRequestInt(connection, i);
            }
            while (true) {
                MavlinkMessage<?> message = connection.next();
                if (message != null) {
                    int systemId = message.getOriginSystemId();
//                    startTimeSeconds = System.currentTimeMillis() / 1000.0;
                    telemetryData.put("sysid", systemId);
                    processTelemetryMessage(message);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processTelemetryMessage(MavlinkMessage<?> message) {


        Object payload = message.getPayload();
        double currentTimeSeconds = System.currentTimeMillis() / 1000.0;
        double timeInAir = currentTimeSeconds - startTimeSeconds;
        telemetryData.put("time_in_air", timeInAir);
        // Format time_in_air_min_sec as minutes.seconds (like 2.35 for 2 min 35 sec)
        int minutes = (int) (timeInAir / 60);
        int seconds = (int) (timeInAir % 60);
        telemetryData.put("time_in_air_min_sec", String.format("%d.%02d", minutes, seconds));
        if(payload instanceof MissionCurrent missionCurrent) {
            telemetryData.put("waypoints_count",missionCurrent.total());
        }

        if (payload instanceof MissionItemInt missionItemInt) {
            System.out.println("Received MissionItemInt: " + missionItemInt.seq());

            // Create a waypoint data map
            Map<String, Object> waypoint = new LinkedHashMap<>();
            waypoint.put("mission_seq", missionItemInt.seq());
            waypoint.put("mission_lat", missionItemInt.x() / 1e7);
            waypoint.put("mission_lon", missionItemInt.y() / 1e7);
            waypoint.put("mission_alt", missionItemInt.z());

            // Add to waypoints list
            waypoints.add(waypoint);
            telemetryData.put("waypoints", waypoints);  // Update telemetry data

            // Print the updated waypoints list
            System.out.println("Waypoints List: " + waypoints);

            Integer waypointsCount = (Integer) telemetryData.get("waypoints_count");
            if (waypointsCount != null && missionItemInt.seq() < waypointsCount - 1) {
                // Request only the next mission item
                sendMissionRequestInt(mavlinkConnection, missionItemInt.seq() + 1);
            }

        }
        if (payload instanceof GlobalPositionInt globalPositionInt) {
            double currentLat = globalPositionInt.lat() / 1e7;
            double currentLon = globalPositionInt.lon() / 1e7;
            double currentAlt = globalPositionInt.alt() / 1000.0;
            double takeoffThreshold = 0.8;
            if (currentAlt > takeoffThreshold && !isAirborne) {
                isAirborne = true;
                startTimeSeconds = System.currentTimeMillis() / 1000.0;
            } else if (currentAlt <= takeoffThreshold && isAirborne) {
                isAirborne = false;
            }

            double distToHome = calculateDistance(currentLat, currentLon, homeLat, homeLon);

            if (prevLat != null && prevLon != null) {
                double distance = calculateDistance(prevLat, prevLon, currentLat, currentLon);
                totalDistance += distance;
                telemetryData.put("dist_traveled", totalDistance);
            }

            telemetryData.put("dist_to_home", distToHome);
            prevLat = currentLat;
            prevLon = currentLon;
            telemetryData.put("lat", currentLat);
            telemetryData.put("lon", currentLon);
            telemetryData.put("alt", currentAlt);


        }

        else if (payload instanceof VfrHud vfrHud) {
            telemetryData.put("airspeed", vfrHud.airspeed());
            telemetryData.put("groundspeed", vfrHud.groundspeed());
            telemetryData.put("vertical_speed", vfrHud.climb());
        } else if (payload instanceof NavControllerOutput navControllerOutput) {
            telemetryData.put("wp_dist", navControllerOutput.wpDist());
        } else if (payload instanceof MissionCurrent missionCurrent) {
            telemetryData.put("waypoints_count", missionCurrent.total());
        } else if (payload instanceof Attitude attitude) {
            telemetryData.put("roll", Math.toDegrees(attitude.roll()));
            telemetryData.put("pitch", Math.toDegrees(attitude.pitch()));
            telemetryData.put("yaw", Math.toDegrees(attitude.yaw()));
        } else if (payload instanceof SysStatus sysStatus) {
            telemetryData.put("battery_voltage", sysStatus.voltageBattery() / 1000.0);
            telemetryData.put("battery_current", sysStatus.currentBattery() / 1000.0);
        } else if (payload instanceof GpsRawInt gpsRawInt) {
            telemetryData.put("gps_hdop", gpsRawInt.eph() / 100.0);
        }
        else if (payload instanceof ServoOutputRaw servoOutputRaw) {
            telemetryData.put("ch3out", servoOutputRaw.servo3Raw());
            telemetryData.put("ch3percent", String.format("%.2f", ((servoOutputRaw.servo3Raw() - 1000.0) / 1000.0) * 100));
            telemetryData.put("ch9out", servoOutputRaw.servo9Raw());
            telemetryData.put("ch10out", servoOutputRaw.servo10Raw());
            telemetryData.put("ch11out", servoOutputRaw.servo11Raw());
            telemetryData.put("ch12out", servoOutputRaw.servo12Raw());
        } else if (payload instanceof Wind wind) {
            telemetryData.put("wind_vel", wind.speed());
        }

        // Calculate tot and toh if groundspeed is available and non-zero.
        Double groundspeed = telemetryData.get("groundspeed") instanceof Number
                ? ((Number) telemetryData.get("groundspeed")).doubleValue() : null;
        Double wp_dist = telemetryData.get("wp_dist") instanceof Number
                ? ((Number) telemetryData.get("wp_dist")).doubleValue() : null;
        Double dist_to_home = telemetryData.get("dist_to_home") instanceof Number
                ? ((Number) telemetryData.get("dist_to_home")).doubleValue() : null;

        if (groundspeed != null && groundspeed > 0) {
            if (wp_dist != null) {
                telemetryData.put("tot", Math.round((wp_dist / groundspeed) * 100.0) / 100.0);
            }
            if (dist_to_home != null) {
                telemetryData.put("toh", Math.round((dist_to_home / groundspeed) * 100.0) / 100.0);
            }
        }
        telemetryService.outputTelemetryData(telemetryData.toString());
    }

    private void emitTelemetry() {
        System.out.println("\033[1;34m--- Telemetry Data ---\033[0m");

        // Get current DateTime
        String timestamp = new SimpleDateFormat("yyyyMMdd_HH'h'mm'm'ss's'").format(new Date());

        // Get GCS IP (replace with actual logic if needed)
        String gcsIP;
        try {
            gcsIP = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            gcsIP = "UnknownIP";
        }

        // Get system ID
        Object sysid = telemetryData.get("sysid");
        String systemID = (sysid != null) ? sysid.toString() : "UnknownSys";


        // Construct filename
        String filename = String.format("Received_%s_%s_%s_t.log",timestamp,"GCSIP_"+gcsIP, "SYSID_"+systemID);

        // Convert telemetry data to string
        String logData = telemetryData.toString();

        // Write to log file
        try (FileWriter fileWriter = new FileWriter(filename, true)) {
            fileWriter.write(logData + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Print telemetry data to console
        telemetryData.forEach((key, value) -> {
            if (key.contains("out")) {
                System.out.printf("\033[91m%-20s\033[0m: %s\n", key, value);
            } else if (key.contains("al") || key.contains("dist") || key.contains("l")) {
                System.out.printf("\033[92m%-20s\033[0m: %s\n", key, value);
            } else {
                System.out.printf("%-20s: %s\n", key, value);
            }
        });
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6371.0; // kilometers
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c; // Returns distance in kilometers
    }
}
