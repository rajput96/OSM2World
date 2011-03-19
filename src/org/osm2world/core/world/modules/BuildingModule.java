package org.osm2world.core.world.modules;

import static org.openstreetmap.josm.plugins.graphview.core.util.ValueStringParser.parseOsmDecimal;
import static org.osm2world.core.world.modules.common.WorldModuleParseUtil.parseHeight;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.osm2world.core.map_data.data.MapArea;
import org.osm2world.core.map_data.data.MapElement;
import org.osm2world.core.map_data.data.overlaps.MapOverlap;
import org.osm2world.core.map_elevation.data.GroundState;
import org.osm2world.core.math.PolygonWithHolesXZ;
import org.osm2world.core.math.SimplePolygonXZ;
import org.osm2world.core.math.TriangleXYZ;
import org.osm2world.core.math.TriangleXZ;
import org.osm2world.core.math.VectorXYZ;
import org.osm2world.core.math.VectorXZ;
import org.osm2world.core.math.algorithms.TriangulationUtil;
import org.osm2world.core.target.Material;
import org.osm2world.core.target.RenderableToAllTargets;
import org.osm2world.core.target.Target;
import org.osm2world.core.target.Material.Lighting;
import org.osm2world.core.terrain.creation.CAGUtil;
import org.osm2world.core.world.data.AreaWorldObject;
import org.osm2world.core.world.data.TerrainBoundaryWorldObject;
import org.osm2world.core.world.modules.common.AbstractModule;

/**
 * adds buildings to the world
 */
public class BuildingModule extends AbstractModule {

	@Override
	protected void applyToArea(MapArea area) {
		
		if (!area.getRepresentations().isEmpty()) return;
		
		String buildingValue = area.getTags().getValue("building");

		if (buildingValue != null && !buildingValue.equals("no")) {
			area.addRepresentation(new Building(area));
		}
		
	}

	private static class Building implements AreaWorldObject,
			RenderableToAllTargets {

		private final MapArea area;
		
		private double height;		
		private Material materialWall;
		private Material materialRoof;
		
		public Building(MapArea area) {

			this.area = area;

			setAttributes();
			
		}

		@Override
		public MapElement getPrimaryMapElement() {
			return area;
		}

		@Override
		public GroundState getGroundState() {
			return GroundState.ON;
		}

		@Override
		public double getClearingAbove(VectorXZ pos) {
			return height;
		}

		@Override
		public double getClearingBelow(VectorXZ pos) {
			return 0;
		}

		@Override
		public void renderTo(Target target) {

			double roofEle = area.getElevationProfile().getMaxEle() + height;
			
			renderWalls(target, roofEle);
			
			renderRoof(target, roofEle);
			
		}

		private void renderRoof(Target target, double roofEle) {
			
			Collection<TriangleXZ> triangles = 
				TriangulationUtil.triangulate(area.getPolygon());

			List<TriangleXYZ> trianglesXYZ = 
				new ArrayList<TriangleXYZ>(triangles.size());
				
			for (TriangleXZ triangle : triangles) {
				trianglesXYZ.add(triangle.makeCounterclockwise().xyz(roofEle));
			}
				
			target.drawTriangles(materialRoof, trianglesXYZ);
			
		}

		private void renderWalls(Target target, double roofEle) {
			
			double floorEle = area.getElevationProfile().getMinEle();
			
			if (area.getOverlaps().isEmpty()) {
				
				renderWalls(target, area.getPolygon(), false, floorEle, roofEle);
				
			} else {
							
				/* find terrain boundaries on the ground 
				 * that overlap with the building */				
				
				List<TerrainBoundaryWorldObject> tbWorldObjects = new ArrayList<TerrainBoundaryWorldObject>();
								
				for (MapOverlap<?,?> overlap : area.getOverlaps()) {
					MapElement other = overlap.getOther(area);
					if (other.getPrimaryRepresentation() instanceof TerrainBoundaryWorldObject
							&& other.getPrimaryRepresentation().getGroundState() == GroundState.ON) {
						tbWorldObjects.add((TerrainBoundaryWorldObject)
								other.getPrimaryRepresentation());						
					}
				}
				
				/* render building parts where the building polygon does not overlap with terrain boundaries */
				
				List<SimplePolygonXZ> subtractPolygons = new ArrayList<SimplePolygonXZ>();
				for (TerrainBoundaryWorldObject o : tbWorldObjects) {
					subtractPolygons.add(o.getOutlinePolygon().getSimpleXZPolygon());
				}
				subtractPolygons.addAll(area.getPolygon().getHoles());
				
				Collection<PolygonWithHolesXZ> buildingPartPolys =
					CAGUtil.subtractPolygons(
							area.getOuterPolygon(), subtractPolygons);
				
				for (PolygonWithHolesXZ p : buildingPartPolys) {
					renderWalls(target, p, false, floorEle, roofEle);
				}
				
				/* render building parts above the terrain boundaries */
				
				for (TerrainBoundaryWorldObject o : tbWorldObjects) {

					Collection<PolygonWithHolesXZ> raisedBuildingPartPolys;
					
					Collection<PolygonWithHolesXZ> polysAboveTBWOs =
						CAGUtil.intersectPolygons(Arrays.asList(
								area.getOuterPolygon(), o.getOutlinePolygon().getSimpleXZPolygon()));
					
					
					if (area.getHoles().isEmpty()) {
						raisedBuildingPartPolys = polysAboveTBWOs;
					} else {						
						raisedBuildingPartPolys = new ArrayList<PolygonWithHolesXZ>();
						for (PolygonWithHolesXZ p : polysAboveTBWOs) {
							List<SimplePolygonXZ> subPolys = new ArrayList<SimplePolygonXZ>();
							subPolys.addAll(area.getPolygon().getHoles());
							subPolys.addAll(p.getHoles());
							raisedBuildingPartPolys.addAll(
									CAGUtil.subtractPolygons(p.getOuter(), subPolys));
						}
					}
					
					for (PolygonWithHolesXZ p : raisedBuildingPartPolys) {
						double clearing = o.getClearingAbove(o.getOutlinePolygon().getSimpleXZPolygon().getCenter());
						renderWalls(target, p, false, 
								area.getElevationProfile().getMaxEle() + clearing,
								roofEle);
					}
					
				}
				
			}
				
		}

		private void renderWalls(Target target, PolygonWithHolesXZ p,
				boolean renderFloor, double floorEle, double roofEle) {
			
			drawWallOnPolygon(target, floorEle, roofEle, p.getOuter().makeCounterclockwise());
			
			for (SimplePolygonXZ polygon : p.getHoles()) {
				drawWallOnPolygon(target, floorEle, roofEle, polygon.makeClockwise());				
			}
			
		}

		private void drawWallOnPolygon(Target target, double roofEle,
				double floorEle, SimplePolygonXZ polygon) {
			
			List<VectorXZ> vertices = polygon.getVertexLoop();
			
			VectorXYZ[] wallVectors = new VectorXYZ[vertices.size() * 2];

			for (int i = 0; i < vertices.size(); i++) {
				final VectorXZ coord = vertices.get(i);
				wallVectors[i*2] = new VectorXYZ(coord.x, floorEle, coord.z);
				wallVectors[i*2 + 1] = new VectorXYZ(coord.x, roofEle, coord.z);
			}

			target.drawTriangleStrip(materialWall, wallVectors);
			
		}
		
		/**
		 * sets the building attributes (height, colors) depending on
		 * the building's tags. If available, explicitly tagged data
		 * is used. Otherwise, the values depend on indirect assumptions
		 * (level height) or ultimately the building class as determined
		 * by the "building" key.
		 */
		private void setAttributes() {
			
			String buildingValue = area.getTags().getValue("building");
			
			double defaultHeight = 10;
			double defaultHeightPerLevel = 2.5;
			Material defaultMaterialWall = new Material(Lighting.FLAT, new Color(1f, 0.9f, 0.55f));
			Material defaultMaterialRoof = new Material(Lighting.FLAT, new Color(0.8f, 0, 0));
			
			if ("greenhouse".equals(buildingValue)) {
				defaultHeight = 2.5;
				defaultMaterialWall = new Material(Lighting.FLAT, new Color(0.9f, 0.9f, 0.9f));
				defaultMaterialRoof = defaultMaterialWall;
			}
			
			Float levels = null;
			if (area.getTags().containsKey("building:levels")) {
				levels = parseOsmDecimal(area.getTags().getValue("building:levels"), false);
			}
			
			double fallbackHeight = (levels == null)
				?  defaultHeight
				: (levels * defaultHeightPerLevel);
			
			height = parseHeight(area.getTags(), (float)fallbackHeight);
			materialWall = defaultMaterialWall;
			materialRoof = defaultMaterialRoof;
						
		}

	}

}
