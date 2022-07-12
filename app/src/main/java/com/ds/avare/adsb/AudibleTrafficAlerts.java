package com.ds.avare.adsb;

import android.content.Context;
import android.location.Location;
import android.media.MediaPlayer;

import com.ds.avare.R;

import java.util.LinkedList;


public class AudibleTrafficAlerts implements Runnable {
    final private MediaPlayer mpTrafficNear;
    final private MediaPlayer mpLow, mpHigh, mpLevel;
    final private MediaPlayer mpOClock;
    final private MediaPlayer[] arrMpClockHours;
    final private SequentialMediaPlayer sequentialMediaPlayer;
    private static volatile Thread runnerThread;
    final private LinkedList<AlertItem> alertQueue;
    private static AudibleTrafficAlerts singleton;

    private static class AlertItem {
        final private Traffic traffic;
        final private Location ownLocation;
        final int ownAltitude;

        private AlertItem(Traffic traffic, Location ownLocation, int ownAltitude) {
            this.ownAltitude = ownAltitude;
            this.traffic = traffic;
            this.ownLocation = ownLocation;
        }

        @Override
        public final int hashCode() {
            return traffic.mCallSign.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (o == null)
                return false;
            if (!(o instanceof AlertItem))
                return false;
            return ((AlertItem)o).traffic.mCallSign.equals(this.traffic.mCallSign);
        }
    }

    private AudibleTrafficAlerts(Context ctx) {
        mpTrafficNear = MediaPlayer.create(ctx, R.raw.watch_out);
        alertQueue = new LinkedList<>();
        sequentialMediaPlayer = new SequentialMediaPlayer(alertQueue);
        arrMpClockHours = new MediaPlayer[] {
                MediaPlayer.create(ctx, R.raw.one), MediaPlayer.create(ctx, R.raw.two), MediaPlayer.create(ctx, R.raw.three),
                MediaPlayer.create(ctx, R.raw.four), MediaPlayer.create(ctx, R.raw.five), MediaPlayer.create(ctx, R.raw.six),
                MediaPlayer.create(ctx, R.raw.seven), MediaPlayer.create(ctx, R.raw.eight), MediaPlayer.create(ctx, R.raw.nine),
                MediaPlayer.create(ctx, R.raw.ten), MediaPlayer.create(ctx, R.raw.eleven), MediaPlayer.create(ctx, R.raw.twelve)
        };
        mpLow = MediaPlayer.create(ctx, R.raw.low);
        mpHigh = MediaPlayer.create(ctx, R.raw.high);
        mpLevel = MediaPlayer.create(ctx, R.raw.level);
        mpOClock = MediaPlayer.create(ctx, R.raw.oclock);

    }

    public synchronized static AudibleTrafficAlerts getAndStartAudibleTrafficAlerts(Context ctx) {
        if (singleton == null)
            singleton = new AudibleTrafficAlerts(ctx);
        runnerThread = new Thread(singleton, "AudibleAlerts");
        runnerThread.start();
        return singleton;
    }

    public static synchronized void stopAudibleTrafficAlerts() {
        if (runnerThread != null) {
            runnerThread.interrupt();
            runnerThread = null;
        }
    }

    public static synchronized  boolean isEnabled() {
        return runnerThread != null && !runnerThread.isInterrupted();
    }

    @Override
    public void run() {
        while(!Thread.currentThread().isInterrupted()) {
            synchronized (alertQueue) {
                if (this.alertQueue.size() > 0 && !sequentialMediaPlayer.isPlaying) {
                    final AlertItem alertItem = alertQueue.removeFirst();
                    final double altitudeDiff = alertItem.ownAltitude - alertItem.traffic.mAltitude;
                    final int clockHour = (int) nearestClockHourFromHeadingAndLocations(
                            alertItem.ownLocation.getLatitude(), alertItem.ownLocation.getLongitude(),
                            alertItem.traffic.mLat, alertItem.traffic.mLon, alertItem.ownLocation.getBearing());
                    if (sequentialMediaPlayer.setMedia(
                        mpTrafficNear, arrMpClockHours[clockHour - 1], mpOClock,
                        Math.abs(altitudeDiff) < 100 ? mpLevel
                                : (altitudeDiff > 0 ? mpLow : mpHigh)
                    )) {
                        sequentialMediaPlayer.play();
                    }
                } else {
                    try {
                        alertQueue.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }

        }

    }

    public  void  alertTrafficPosition(Traffic traffic, Location myLoc, int ownAltitude) {
        synchronized (alertQueue) {
            final AlertItem alertItem = new AlertItem(traffic, myLoc, ownAltitude);
            final int alertIndex = alertQueue.indexOf(alertItem);
            if (alertIndex == -1) {
                this.alertQueue.add(alertItem);
            } else {    // if already in queue, update with the most recent data prior to speaking
                this.alertQueue.set(alertIndex, alertItem);
            }
            alertQueue.notifyAll();
        }
    }

    /**
     * Helpler class that uses media event handling to ensure strictly sequential play of a list
     * of media resources
     */
    private static class SequentialMediaPlayer implements MediaPlayer.OnCompletionListener {

        private MediaPlayer[] media;
        private boolean isPlaying = false;
        private int mediaIndex = 0;
        final private Object playStatusMonitorObject;

        SequentialMediaPlayer(Object playStatusMonitorObject) {
            if (playStatusMonitorObject == null)
                throw new IllegalArgumentException("Play status monitor object must not be null");
            this.playStatusMonitorObject = playStatusMonitorObject;
        }

        /**
         * @param media Media item sequence to queue in player
         */
        public synchronized boolean setMedia(MediaPlayer... media) {
            if (!isPlaying) {
                this.media = media;
                this.mediaIndex = 0;
                for (MediaPlayer mp : media)
                    mp.setOnCompletionListener(this);
                return true;
            } else
                return false;
        }

        @Override
        public void onCompletion(MediaPlayer mediaPlayer) {
            if (++mediaIndex <= media.length-1)
                play();
            else {
                this.isPlaying = false;
                synchronized(playStatusMonitorObject) {
                    playStatusMonitorObject.notifyAll();
                }
            }
        }

        public synchronized void play() {
            if (media == null || mediaIndex > media.length-1)
                throw new IllegalStateException("No more media to play; finished sequence or no media set");
            isPlaying = true;
            media[mediaIndex].start();
        }
    }

    protected static double angleFromCoordinate(double lat1, double long1, double lat2,
                                              double long2) {

        final double lat1Rad = Math.toRadians(lat1);
        final double long1Rad = Math.toRadians(long1);
        final double lat2Rad = Math.toRadians(lat2);
        final double long2Rad = Math.toRadians(long2);

        final double dLon = (long2Rad - long1Rad);

        final double y = Math.sin(dLon) * Math.cos(lat2Rad);
        final double x = Math.cos(lat1Rad) * Math.sin(lat2Rad) - Math.sin(lat1Rad)
                * Math.cos(lat2Rad) * Math.cos(dLon);

        final double bearingRad = Math.atan2(y, x);

        return  (Math.toDegrees(bearingRad) + 360) % 360;
    }

    protected static long nearestClockHourFromHeadingAndLocations(
            double lat1, double long1, double lat2, double long2,  double myBearing) {
        final long nearestClockHour = Math.round(relativeBearingFromHeadingAndLocations(lat1, long1, lat2, long2, myBearing)/30.0);
        return nearestClockHour != 0 ? nearestClockHour : 12;
    }

    protected static double relativeBearingFromHeadingAndLocations(double lat1, double long1, double lat2, double long2,  double myBearing) {
        return (angleFromCoordinate(lat1, long1, lat2, long2) - myBearing + 360) % 360;
    }
}