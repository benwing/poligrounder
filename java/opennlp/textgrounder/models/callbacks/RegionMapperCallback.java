///////////////////////////////////////////////////////////////////////////////
//  Copyright (C) 2010 Taesun Moon, The University of Texas at Austin
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
package opennlp.textgrounder.models.callbacks;

import gnu.trove.TIntHashSet;
import gnu.trove.TIntObjectHashMap;
import gnu.trove.TIntObjectIterator;
import gnu.trove.TObjectIntHashMap;
import java.util.HashSet;

import opennlp.textgrounder.textstructs.Lexicon;
import opennlp.textgrounder.topostructs.*;

/**
 * A callback class to 
 *
 * @author tsmoon
 */
public class RegionMapperCallback<E extends SmallLocation> {

    /**
     * Table from index to region
     */
    protected TIntObjectHashMap<Region> idxToRegionMap;
    /**
     * Table from region to index. Reverse storage table for idxToRegionMap.
     */
    protected TObjectIntHashMap<Region> regionToIdxMap;
    /**
     * Table from placename to set of region indexes. The indexes and their
     * referents are stored in idxToRegionMap.
     */
    protected TIntObjectHashMap<TIntHashSet> placenameIdxToRegionIndexSet;
    /**
     * Temporary variable for keeping track of regions that have been assigned
     * to the current location
     */
    protected HashSet<LocationRegionPair> currentLocationRegions;
    /**
     * 
     */
    protected TIntObjectHashMap<TIntHashSet> toponymRegionToLocationIndexSet;
    /**
     *
     */
    protected int numRegions;

    /**
     *
     */
    public RegionMapperCallback() {
        numRegions = 0;
        idxToRegionMap = new TIntObjectHashMap<Region>();
        regionToIdxMap = new TObjectIntHashMap<Region>();
        placenameIdxToRegionIndexSet = new TIntObjectHashMap<TIntHashSet>();
        toponymRegionToLocationIndexSet = new TIntObjectHashMap<TIntHashSet>();
        currentLocationRegions = new HashSet<LocationRegionPair>();
    }

    /**
     *
     * @param _loc
     * @param _region
     */
    public void addToPlace(E _loc, Region _region) {
        if (!regionToIdxMap.containsKey(_region)) {
            regionToIdxMap.put(_region, numRegions);
            idxToRegionMap.put(numRegions, _region);
            numRegions += 1;
        }
        int regionid = regionToIdxMap.get(_region);
        LocationRegionPair locationRegionPair = new LocationRegionPair(_loc, regionid);
        currentLocationRegions.add(locationRegionPair);
    }

    /**
     *
     * @param placename
     * @param lexicon
     */
    public void addAll(String placename, Lexicon lexicon) {
        int wordid = lexicon.getIntForWord(placename);
        addAll(wordid);
    }

    public void addAll(int placeid) {
        if (!placenameIdxToRegionIndexSet.containsKey(placeid)) {
            placenameIdxToRegionIndexSet.put(placeid, new TIntHashSet());
        }
        TIntHashSet currentRegions = placenameIdxToRegionIndexSet.get(placeid);

        for (LocationRegionPair locationRegionPair : currentLocationRegions) {
            ToponymRegionPair toponymRegionPair = new ToponymRegionPair(placeid, locationRegionPair.regionIndex);
            if (!toponymRegionToLocationIndexSet.containsKey(toponymRegionPair.hashCode())) {
                toponymRegionToLocationIndexSet.put(toponymRegionPair.hashCode(), new TIntHashSet());
            }
            toponymRegionToLocationIndexSet.get(toponymRegionPair.hashCode()).add(locationRegionPair.locationIndex);
            currentRegions.add(locationRegionPair.regionIndex);
        }
        currentLocationRegions = new HashSet<LocationRegionPair>();
    }

    public void trimToSize() {
        idxToRegionMap.trimToSize();
        regionToIdxMap.trimToSize();
        for (TIntObjectIterator<TIntHashSet> it = placenameIdxToRegionIndexSet.iterator();
              it.hasNext();) {
            it.advance();
            it.value().trimToSize();
        }
        placenameIdxToRegionIndexSet.trimToSize();

        for (TIntObjectIterator<TIntHashSet> it = toponymRegionToLocationIndexSet.iterator();
              it.hasNext();) {
            it.advance();
            it.value().trimToSize();
        }
        toponymRegionToLocationIndexSet.trimToSize();
    }

    /**
     * @return the idxToRegionMap
     */
    public TIntObjectHashMap<Region> getIdxToRegionMap() {
        return idxToRegionMap;
    }

    /**
     * @return the regionToIdxMap
     */
    public TObjectIntHashMap<Region> getRegionToIdxMap() {
        return regionToIdxMap;
    }

    /**
     * @return the placenameIdxToRegionIndexSet
     */
    public TIntObjectHashMap<TIntHashSet> getPlacenameIdxToRegionIndexSet() {
        return placenameIdxToRegionIndexSet;
    }

    /**
     * @return the numRegions
     */
    public int getNumRegions() {
        return numRegions;
    }

    /**
     * @return the toponymRegionToLocationIndexSet
     */
    public TIntObjectHashMap<TIntHashSet> getToponymRegionToLocations() {
        return toponymRegionToLocationIndexSet;
    }
}
