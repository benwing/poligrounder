///////////////////////////////////////////////////////////////////////////////
//  Copyright (C) 2010 Travis Brown, The University of Texas at Austin
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
///////////////////////////////////////////////////////////////////////////////
package opennlp.textgrounder.topo;

public class Coordinate {
    private final double lng;
    private final double lat;
    
    public Coordinate(double lat, double lng) {
      this.lng = lng;
      this.lat = lat;
    }

    public double getLat() {
      return this.lat;
    }

    public double getLng() {
      return this.lng;
    }

    public double getLatDegrees() {
      return this.lat * 180.0 / Math.PI;
    }

    public double getLngDegrees() {
      return this.lng * 180.0 / Math.PI;
    }

    /**
     * Compare two coordinates to see if they're sufficiently close together.
     * @param other The other coordinate being compared
     * @param maxDiff Both lat and lng must be within this value
     * @return Whether the two coordinates are sufficiently close
     */
    public boolean looselyMatches(Coordinate other, double maxDiff) {
	return Math.abs(this.lat - other.lat) <= maxDiff &&
               Math.abs(this.lng - other.lng) <= maxDiff;
    }

    /**
     * Generate a new Coordinate that is the `n'th point along a spiral
     * radiating outward from the given coordinate. `initRadius' controls where
     * on the spiral the zeroth point is located. The constant local variable
     * `radianUnit' controls the spacing of the points (FIXME, should be an
     * optional parameter). The radius of the spiral increases by 1/10 (FIXME,
     * should be controllable) of `initRadius' every point.
     * 
     * @param n
     *            How far along the spiral to return a coordinate for
     * @param initRadius
     *            Where along the spiral the 0th point is located; this also
     *            controls how quickly the spiral grows outward
     * @return A new coordinate along the spiral
     */
    public Coordinate getNthSpiralPoint(int n, double initRadius) {
      if (n == 0) {
        return this;
      }

      final double radianUnit = Math.PI / 10.0;
      double radius = initRadius + (initRadius * 0.1) * n;
      double angle = radianUnit / 2.0 + 1.1 * radianUnit * n;

      return new Coordinate(this.lat + radius * Math.cos(angle), this.lng + radius * Math.sin(angle));
    }

    public String toString() {
      return String.format("%.08f,%.08f", this.getLatDegrees(), this.getLngDegrees());
    }

    public double distance(Coordinate other) {
      return Math.acos(Math.sin(this.lat) * Math.sin(other.lat)
           + Math.cos(this.lat) * Math.cos(other.lat) * Math.cos(other.lng - this.lng));
    }

    @Override
    public boolean equals(Object other) {
      return other != null &&
             other.getClass() == this.getClass() &&
             ((Coordinate) other).lat == this.lat &&
             ((Coordinate) other).lng == this.lng;
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 29 * hash + (int) (Double.doubleToLongBits(this.lng) ^ (Double.doubleToLongBits(this.lng) >>> 32));
        hash = 29 * hash + (int) (Double.doubleToLongBits(this.lat) ^ (Double.doubleToLongBits(this.lat) >>> 32));
        return hash;
    }
}
