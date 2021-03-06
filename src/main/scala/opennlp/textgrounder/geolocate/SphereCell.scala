///////////////////////////////////////////////////////////////////////////////
//  SphereCell.scala
//
//  Copyright (C) 2011 Ben Wing, The University of Texas at Austin
//  Copyright (C) 2012 Stephen Roller, The University of Texas at Austin
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

package opennlp.textgrounder.geolocate

import opennlp.textgrounder.util.distances._

import opennlp.textgrounder.gridlocate.{GeoCell,CellGrid}

/////////////////////////////////////////////////////////////////////////////
//                             Cells in a grid                             //
/////////////////////////////////////////////////////////////////////////////

abstract class SphereCell(
  cell_grid: SphereCellGrid
) extends GeoCell[SphereCoord, SphereDocument](cell_grid) {
  /**
   * Generate KML for a single cell.
   */
  def generate_kml(xfprob: Double, xf_minprob: Double, xf_maxprob: Double,
    params: KMLParameters): Iterable[xml.Elem]
}

/**
 * A cell in a polygonal shape.
 *
 * @param cell_grid The CellGrid object for the grid this cell is in.
 */
abstract class PolygonalCell(
  cell_grid: SphereCellGrid
) extends SphereCell(cell_grid) {
  /**
   * Return the boundary of the cell as an Iterable of coordinates, tracing
   * out the boundary vertex by vertex.  The last coordinate should be the
   * same as the first, as befits a closed shape.
   */
  def get_boundary(): Iterable[SphereCoord]

  /**
   * Return the "inner boundary" -- something echoing the actual boundary of the
   * cell but with smaller dimensions.  Used for outputting KML to make the
   * output easier to read.
   */
  def get_inner_boundary() = {
    val center = get_center_coord()
    for (coord <- get_boundary())
      yield SphereCoord((center.lat + coord.lat) / 2.0,
                  average_longitudes(center.long, coord.long))
  }

  /**
   * Generate the KML placemark for the cell's name.  Currently it's rectangular
   * for rectangular cells.  FIXME: Perhaps it should be generalized so it doesn't
   * need to be redefined for differently-shaped cells.
   *
   * @param name The name to display in the placemark
   */
  def generate_kml_name_placemark(name: String): xml.Elem

  def generate_kml(xfprob: Double, xf_minprob: Double, xf_maxprob: Double,
      params: KMLParameters) = {
    val offprob = xfprob - xf_minprob
    val fracprob = offprob / (xf_maxprob - xf_minprob)
    var coordtext = "\n"
    for (coord <- get_inner_boundary()) {
      coordtext += "%s,%s,%s\n" format (
        coord.long, coord.lat, fracprob * params.kml_max_height)
    }
    val name =
      if (most_popular_document != null) most_popular_document.title
      else ""

    // Placemark indicating name
    val name_placemark = generate_kml_name_placemark(name)

    // Interpolate colors
    val color = Array(0.0, 0.0, 0.0)
    for (i <- 0 until 3) {
      color(i) = (params.kml_mincolor(i) +
        fracprob * (params.kml_maxcolor(i) - params.kml_mincolor(i)))
    }
    // Original color dc0155ff
    //rgbcolor = "dc0155ff"
    val revcol = color.reverse
    val rgbcolor = "ff%02x%02x%02x" format (
      revcol(0).toInt, revcol(1).toInt, revcol(2).toInt)

    // Yield cylinder indicating probability by height and color

    // !!PY2SCALA: BEGIN_PASSTHRU
    val cylinder_placemark =
      <Placemark>
        <name>{ "%s POLYGON" format name }</name>
        <styleUrl>#bar</styleUrl>
        <Style>
          <PolyStyle>
            <color>{ rgbcolor }</color>
            <colorMode>normal</colorMode>
          </PolyStyle>
        </Style>
        <Polygon>
          <extrude>1</extrude>
          <tessellate>1</tessellate>
          <altitudeMode>relativeToGround</altitudeMode>
          <outerBoundaryIs>
            <LinearRing>
              <coordinates>{ coordtext }</coordinates>
            </LinearRing>
          </outerBoundaryIs>
        </Polygon>
      </Placemark>
    // !!PY2SCALA: END_PASSTHRU
    Seq(name_placemark, cylinder_placemark)
  }
}

/**
 * A cell in a rectangular shape.
 *
 * @param cell_grid The CellGrid object for the grid this cell is in.
 */
abstract class RectangularCell(
  cell_grid: SphereCellGrid
) extends PolygonalCell(cell_grid) {
  /**
   * Return the coordinate of the southwest point of the rectangle.
   */
  def get_southwest_coord(): SphereCoord
  /**
   * Return the coordinate of the northeast point of the rectangle.
   */
  def get_northeast_coord(): SphereCoord

  /**
   * Define the center based on the southwest and northeast points,
   * or based on the centroid of the cell.
   */
  var centroid: Array[Double] = new Array[Double](2)
  var num_docs: Int = 0

  def get_center_coord() = {
    if (num_docs == 0 || cell_grid.table.driver.params.center_method == "center") {
      // use the actual cell center
      // also, if we have an empty cell, there is no such thing as
      // a centroid, so default to the center
      val sw = get_southwest_coord()
      val ne = get_northeast_coord()
      SphereCoord((sw.lat + ne.lat) / 2.0, (sw.long + ne.long) / 2.0)
    } else {
      // use the centroid
      SphereCoord(centroid(0) / num_docs, centroid(1) / num_docs);
    }
  }

  override def add_document(document: SphereDocument) {
    num_docs += 1
    centroid(0) += document.coord.lat
    centroid(1) += document.coord.long
    super.add_document(document)
  }



  /**
   * Define the boundary given the specified southwest and northeast
   * points.
   */
  def get_boundary() = {
    val sw = get_southwest_coord()
    val ne = get_northeast_coord()
    val center = get_center_coord()
    val nw = SphereCoord(ne.lat, sw.long)
    val se = SphereCoord(sw.lat, ne.long)
    Seq(sw, nw, ne, se, sw)
  }

  /**
   * Generate the name placemark as a smaller rectangle within the
   * larger rectangle. (FIXME: Currently it is exactly the size of
   * the inner boundary.  Perhaps this should be generalized, so
   * that the definition of this function can be handled up at the
   * polygonal-shaped-cell level.)
   */
  def generate_kml_name_placemark(name: String) = {
    val sw = get_southwest_coord()
    val ne = get_northeast_coord()
    val center = get_center_coord()
    // !!PY2SCALA: BEGIN_PASSTHRU
    // Because it tries to frob the # sign
    <Placemark>
      <name>{ name }</name>
      ,
      <Cell>
        <LatLonAltBox>
          <north>{ ((center.lat + ne.lat) / 2).toString }</north>
          <south>{ ((center.lat + sw.lat) / 2).toString }</south>
          <east>{ ((center.long + ne.long) / 2).toString }</east>
          <west>{ ((center.long + sw.long) / 2).toString }</west>
        </LatLonAltBox>
        <Lod>
          <minLodPixels>16</minLodPixels>
        </Lod>
      </Cell>
      <styleURL>#bar</styleURL>
      <Point>
        <coordinates>{ "%s,%s" format (center.long, center.lat) }</coordinates>
      </Point>
    </Placemark>
    // !!PY2SCALA: END_PASSTHRU
  }
}

/**
 * Abstract class for a grid of cells covering the earth.
 */
abstract class SphereCellGrid(
  override val table: SphereDocumentTable
) extends CellGrid[SphereCoord, SphereDocument, SphereCell](table) {
}

