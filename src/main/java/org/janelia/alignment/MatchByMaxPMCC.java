/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment;

import ij.ImagePlus;
import ij.process.FloatProcessor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.io.FileWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import mpicbg.models.AbstractModel;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.SpringMesh;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.Vertex;
import mpicbg.util.Util;
import mpicbg.ij.blockmatching.BlockMatching;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

/**
 * <pre>java -cp fijibento.jar org.janelia.alignment.MatchByMaxPMCC \
 *   --inputfile1 "tilespec1" \
 *   --inputfile2 "tilespec2" \
 * 
 * @author Seymour Knowles-Barley
 */
public class MatchByMaxPMCC
{
	@Parameters
	static private class Params
	{
		@Parameter( names = "--help", description = "Display this note", help = true )
        private final boolean help = false;

        @Parameter( names = "--inputfile", description = "Tilespec file", required = true )
        private String inputfile;
        
        /*
        @Parameter( names = "--corrfile", description = "Correspondence file from the sift features matching (includes models between tiles)", required = true )
        private String corrfile;
        */

        @Parameter( names = "--fixedTiles", description = "Fixed tiles indices (space separated)", variableArity = true, required = true )
        public List<Integer> fixedTiles = new ArrayList<Integer>();
        
        @Parameter( names = "--targetPath", description = "Path for the output correspondences", required = true )
        public String targetPath;
        
        @Parameter( names = "--indices", description = "Pair of indices within feature file, comma separated (each pair is separated by a colon)", required = true )
        public List<String> indices = new ArrayList<String>();
        
        @Parameter( names = "--layerScale", description = "Layer scale", required = false )
        public float layerScale = 0.5f;
        
        @Parameter( names = "--searchRadius", description = "Search window radius", required = false )
        public int searchRadius = 20;
        
        @Parameter( names = "--blockRadius", description = "Matching block radius", required = false )
        public int blockRadius = 50;
                
        @Parameter( names = "--resolutionSpringMesh", description = "resolutionSpringMesh", required = false )
        public int resolutionSpringMesh = 32;
        
        @Parameter( names = "--minR", description = "minR", required = false )
        public float minR = 0.5f;
        
        @Parameter( names = "--maxCurvatureR", description = "maxCurvatureR", required = false )
        public float maxCurvatureR = 10f;
        
        @Parameter( names = "--rodR", description = "rodR", required = false )
        public float rodR = 0.9f;
        
        @Parameter( names = "--useLocalSmoothnessFilter", description = "useLocalSmoothnessFilter", required = false )
        public boolean useLocalSmoothnessFilter = false;
        
        @Parameter( names = "--localModelIndex", description = "localModelIndex", required = false )
        public int localModelIndex = 1;
        // 0 = "Translation", 1 = "Rigid", 2 = "Similarity", 3 = "Affine"
        
        @Parameter( names = "--localRegionSigma", description = "localRegionSigma", required = false )
        public float localRegionSigma = 25f;
        
        @Parameter( names = "--maxLocalEpsilon", description = "maxLocalEpsilon", required = false )
        public float maxLocalEpsilon = 12f;
        
        @Parameter( names = "--maxLocalTrust", description = "maxLocalTrust", required = false )
        public int maxLocalTrust = 3;
        
        @Parameter( names = "--stiffnessSpringMesh", description = "stiffnessSpringMesh", required = false )
        public float stiffnessSpringMesh = 0.1f;
		
        @Parameter( names = "--dampSpringMesh", description = "dampSpringMesh", required = false )
        public float dampSpringMesh = 0.9f;
		
        @Parameter( names = "--maxStretchSpringMesh", description = "maxStretchSpringMesh", required = false )
        public float maxStretchSpringMesh = 2000.0f;

        @Parameter( names = "--springLengthSpringMesh", description = "spring_length", required = false )
        public float springLengthSpringMesh = 100.0f;

        @Parameter( names = "--threads", description = "Number of threads to be used", required = false )
        public int numThreads = Runtime.getRuntime().availableProcessors();

        @Parameter( names = "--saveAndUseIntermediateResults", description = "Saves the matches per two tiles (used when running on the cluster)", required = false )
        public boolean saveAndUseIntermediateResults = false;
        
	}
	
	private MatchByMaxPMCC() {}
	
	private static String getIntermediateFileName( final String targetPath, final int index1, final int index2 )
	{
		String outputFile = targetPath.substring( 0, targetPath.lastIndexOf( '.' ) ) +
				"_" + index1 + "_" + index2 + ".json";
		return outputFile;
	}

	private static List< PointMatch > loadIntermediateResults(
			final Params params,
			final int index1,
			final int index2 )
	{
		List< PointMatch > res = null;
		
		final String inputFileName = getIntermediateFileName( params. targetPath, index1, index2 );
		File inFile = new File( inputFileName );
		if ( inFile.exists() )
		{
			System.out.println( "Intermediate file: " + inputFileName + " exists, loading data" );
			// Open and parse the json file
			final CorrespondenceSpec[] corr_data;
			try
			{
				final Gson gson = new Gson();
				corr_data = gson.fromJson( new InputStreamReader( new FileInputStream( inFile ) ),
							CorrespondenceSpec[].class );
			}
			catch ( final JsonSyntaxException e )
			{
				System.err.println( "JSON syntax malformed." );
				e.printStackTrace( System.err );
				return null;
			}
			catch ( final Exception e )
			{
				e.printStackTrace( System.err );
				return null;
			}
			
			if ( corr_data != null )
			{
				// There should only be a single correspondence points list in the intermediate files
				assert( corr_data.length == 1 );
				
				res = corr_data[0].correspondencePointPairs;
			}

		}
		
		return res;
	}
	
	private static void saveIntermediateResults(
			final List< PointMatch > pm_strip, 
			final int mipmapLevel,
			final String imageUrl1,
			final String imageUrl2,
			final Params params,
			final int index1,
			final int index2 )
	{
		List< CorrespondenceSpec > tiles_correspondence = new ArrayList< CorrespondenceSpec >();

		tiles_correspondence.add(new CorrespondenceSpec(
				mipmapLevel,
				imageUrl1,
				imageUrl2,
				pm_strip));

		try {
			String outputFile = getIntermediateFileName( params.targetPath, index1, index2 );
			System.out.println( "Saving intermediate result to: " + outputFile );
			Writer writer = new FileWriter(outputFile);
	        //Gson gson = new GsonBuilder().create();
	        Gson gson = new GsonBuilder().setPrettyPrinting().create();
	        gson.toJson(tiles_correspondence, writer);
	        writer.close();
	    }
		catch ( final IOException e )
		{
			System.err.println( "Error writing JSON file: " + params.targetPath );
			e.printStackTrace( System.err );
		}
	}
	
	public static void main( final String[] args )
	{
		
		final Params params = new Params();
		
		try
        {
			final JCommander jc = new JCommander( params, args );
        	if ( params.help )
            {
        		jc.usage();
                return;
            }
        }
        catch ( final Exception e )
        {
        	e.printStackTrace();
            final JCommander jc = new JCommander( params );
        	jc.setProgramName( "java [-options] -cp render.jar org.janelia.alignment.RenderTile" );
        	jc.usage(); 
        	return;
        }
		
		/* open tilespec1 */
		final TileSpec[] tileSpecs;
		try
		{
			final Gson gson = new Gson();
			URL url = new URL( params.inputfile );
			tileSpecs = gson.fromJson( new InputStreamReader( url.openStream() ), TileSpec[].class );
		}
		catch ( final MalformedURLException e )
		{
			System.err.println( "URL malformed." );
			e.printStackTrace( System.err );
			return;
		}
		catch ( final JsonSyntaxException e )
		{
			System.err.println( "JSON syntax malformed." );
			e.printStackTrace( System.err );
			return;
		}
		catch ( final Exception e )
		{
			e.printStackTrace( System.err );
			return;
		}
		
		/*
		final CorrespondenceSpec[] sift_matches_corr_data;
		try
		{
			final Gson gson = new Gson();
			URL url = new URL( params.corrfile );
			sift_matches_corr_data = gson.fromJson( new InputStreamReader( url.openStream() ), CorrespondenceSpec[].class );
		}
		catch ( final MalformedURLException e )
		{
			System.err.println( "URL malformed." );
			e.printStackTrace( System.err );
			return;
		}
		catch ( final JsonSyntaxException e )
		{
			System.err.println( "JSON syntax malformed." );
			e.printStackTrace( System.err );
			return;
		}
		catch ( final Exception e )
		{
			e.printStackTrace( System.err );
			return;
		}
		*/
		
        ij.Prefs.setThreads( params.numThreads );

		// The mipmap level to work on
		// TODO: Should be a parameter from the user,
		//       and decide whether or not to create the mipmaps if they are missing
		int mipmapLevel = 0;

		List< CorrespondenceSpec > corr_data = new ArrayList< CorrespondenceSpec >();

		// Create the meshes for the tiles
        final List< SpringMesh > meshes = Utils.createMeshes( 
        		tileSpecs, params.springLengthSpringMesh, params.stiffnessSpringMesh,
        		params.maxStretchSpringMesh, params.layerScale, params.dampSpringMesh);
        
		for (String idx_pair : params.indices) {
			String[] vals = idx_pair.split(":");
			if (vals.length != 2)
				throw new IllegalArgumentException("Index pair not in correct format:" + idx_pair);
			int idx1 = Integer.parseInt(vals[0]);
			int idx2 = Integer.parseInt(vals[1]);

			TileSpec ts1 = tileSpecs[idx1];
			TileSpec ts2 = tileSpecs[idx2];
			
			final ArrayList< PointMatch > pm12 = new ArrayList< PointMatch >();
			final ArrayList< PointMatch > pm21 = new ArrayList< PointMatch >();
			List< PointMatch > pm12_strip = new ArrayList< PointMatch >();
			List< PointMatch > pm21_strip = new ArrayList< PointMatch >();
	
			/* load image TODO use Bioformats for strange formats */
			final String imageUrl1 = ts1.getMipmapLevels().get( String.valueOf( mipmapLevel ) ).imageUrl;
			final String imageUrl2 = ts2.getMipmapLevels().get( String.valueOf( mipmapLevel ) ).imageUrl;
			final ImagePlus imp1 = Utils.openImagePlus( imageUrl1.replaceFirst("file://", "").replaceFirst("file:/", "") );
			final ImagePlus imp2 = Utils.openImagePlus( imageUrl2.replaceFirst("file://", "").replaceFirst("file:/", "") );
	
			//final SpringMesh m1 = Utils.getMesh( imp1.getWidth(), imp1.getHeight(), 1.0f, params.resolutionSpringMesh, params.stiffnessSpringMesh, params.dampSpringMesh, params.maxStretchSpringMesh );
			//final SpringMesh m2 = Utils.getMesh( imp2.getWidth(), imp2.getHeight(), 1.0f, params.resolutionSpringMesh, params.stiffnessSpringMesh, params.dampSpringMesh, params.maxStretchSpringMesh );
			final SpringMesh m1 = meshes.get( idx1 );
			final SpringMesh m2 = meshes.get( idx2 );
	
			final ArrayList< Vertex > v1 = m1.getVertices();
			final ArrayList< Vertex > v2 = m2.getVertices();
	
			final CoordinateTransformList< CoordinateTransform > ctl1 = ts1.createTransformList();
			final CoordinateTransformList< CoordinateTransform > ctl2 = ts2.createTransformList();
					
			/* TODO: masks? */
			/* calculate block matches */
	
			final AbstractModel< ? > localSmoothnessFilterModel = Utils.createModel( params.localModelIndex );
	
			//final FloatProcessor ip1 = ( FloatProcessor )imp1.getProcessor().convertToFloat().duplicate();
			//final FloatProcessor ip2 = ( FloatProcessor )imp2.getProcessor().convertToFloat().duplicate();
			final FloatProcessor ip1 = ( FloatProcessor )imp1.getProcessor().convertToFloat();
			final FloatProcessor ip2 = ( FloatProcessor )imp2.getProcessor().convertToFloat();
			
			
			//final int blockRadius = Math.max( 16, mpicbg.util.Util.roundPos( params.layerScale * params.blockRadius ) );
	        //final int searchRadius = Math.round( params.layerScale * params.searchRadius );
			final int blockRadius = Math.max( Util.roundPos( 16 / params.layerScale ), params.blockRadius );
			final int searchRadius = params.searchRadius;
	
			final CoordinateTransformList< CoordinateTransform > transform12 = Utils.getInverseModel( ctl1 );
			transform12.add( ctl2 );
			
			final CoordinateTransformList< CoordinateTransform > transform21 = Utils.getInverseModel( ctl2 );
			transform21.add( ctl1 );

//			final TranslationModel2D transform12 = (( TranslationModel2D )ctl1.get(0)).createInverse();
//			transform12.concatenate( (( TranslationModel2D )( Object )ctl2.get(0)) );


			if ( !params.fixedTiles.contains( idx1 ) )
			{
				System.out.println( "Matching: " + imageUrl1 + " > " + imageUrl2 );
				boolean alreadyCalculated = false;
				
				if ( params.saveAndUseIntermediateResults )
				{
					// try loading the data
					List< PointMatch > intermediateResult = loadIntermediateResults( params, idx1, idx2 );
					if ( intermediateResult == null )
						System.out.println( "Intermediate result between " + idx1 + " and " + idx2 + " was not found. Matching..." );
					else
					{
						pm12_strip = intermediateResult;
						alreadyCalculated = true;
					}
				}

				if ( !alreadyCalculated )
				{
					try{
						BlockMatching.matchByMaximalPMCC(
								ip1,
								ip2,
								null, //mask1
								null, //mask2
								params.layerScale, //Math.min( 1.0f, ( float )params.maxImageSize / ip1.getWidth() ),
								transform21,
								blockRadius,
								blockRadius,
								searchRadius,
								searchRadius,
								params.minR,
								params.rodR,
								params.maxCurvatureR,
								v1,
								pm12,
								new ErrorStatistic( 1 ) );
					}
					catch ( final Exception e )
					{
						e.printStackTrace( System.err );
						return;
					}
			
					if ( params.useLocalSmoothnessFilter )
					{
						System.out.println( imageUrl1 + " > " + imageUrl2 + ": found " + pm12.size() + " correspondence candidates." );
						localSmoothnessFilterModel.localSmoothnessFilter( pm12, pm12, params.localRegionSigma, params.maxLocalEpsilon, params.maxLocalTrust );
						System.out.println( imageUrl1 + " > " + imageUrl2 + ": " + pm12.size() + " candidates passed local smoothness filter." );
					}
					else
					{
						System.out.println( imageUrl1 + " > " + imageUrl2 + ": found " + pm12.size() + " correspondences." );
					}
	
					// Remove Vertex (spring mesh) details from points
					for (PointMatch pm: pm12)
					{
						PointMatch actualPm = new PointMatch(
								new Point( pm.getP1().getL(), ctl1.apply( pm.getP1().getW() ) ),
								new Point( pm.getP2().getL(), ctl2.apply( pm.getP2().getW() ) )
								);
						pm12_strip.add( actualPm );
					}
	
					if ( params.saveAndUseIntermediateResults )
					{
						saveIntermediateResults( pm12_strip, mipmapLevel, imageUrl1, imageUrl2, params, idx1, idx2 );
					}
				}
			}
			else
            {
				System.out.println( "Skipping fixed tile " + idx1 );
            }
	
			if ( !params.fixedTiles.contains( idx2 ) )
			{
				System.out.println( "Matching: " + imageUrl1 + " < " + imageUrl2 );
				boolean alreadyCalculated = false;
				
				if ( params.saveAndUseIntermediateResults )
				{
					// try loading the data
					List< PointMatch > intermediateResult = loadIntermediateResults( params, idx2, idx1 );
					if ( intermediateResult == null )
						System.out.println( "Intermediate result between " + idx2 + " and " + idx1 + " was not found. Matching..." );
					else
					{
						pm21_strip = intermediateResult;
						alreadyCalculated = true;
					}
				}

				if ( !alreadyCalculated )
				{
					try{
					BlockMatching.matchByMaximalPMCC(
							ip2,
							ip1,
							null, //mask2
							null, //mask1
							params.layerScale, //Math.min( 1.0f, ( float )p.maxImageSize / ip2.getWidth() ),
							transform12,
							blockRadius,
							blockRadius,
							searchRadius,
							searchRadius,
							params.minR,
							params.rodR,
							params.maxCurvatureR,
							v2,
							pm21,
							new ErrorStatistic( 1 ) );
					}
					catch ( final Exception e )
					{
						e.printStackTrace( System.err );
						return;
					}
			
			
					if ( params.useLocalSmoothnessFilter )
					{
						System.out.println( imageUrl1 + " < " + imageUrl2 + ": found " + pm21.size() + " correspondence candidates." );
						localSmoothnessFilterModel.localSmoothnessFilter( pm21, pm21, params.localRegionSigma, params.maxLocalEpsilon, params.maxLocalTrust );
						System.out.println( imageUrl1 + " < " + imageUrl2 + ": " + pm21.size() + " candidates passed local smoothness filter." );
					}
					else
					{
						System.out.println( imageUrl1 + " < " + imageUrl2 + ": found " + pm21.size() + " correspondences." );
					}
	
					// Remove Vertex (spring mesh) details from points
					for (PointMatch pm: pm21)
					{
						PointMatch actualPm = new PointMatch(
								new Point( pm.getP1().getL(), ctl2.apply( pm.getP1().getW() ) ),
								new Point( pm.getP2().getL(), ctl1.apply( pm.getP2().getW() ) )
								);
						pm21_strip.add( actualPm );
					}
					
					if ( params.saveAndUseIntermediateResults )
					{
						saveIntermediateResults( pm21_strip, mipmapLevel, imageUrl2, imageUrl1, params, idx2, idx1 );
					}
				}
			}
			else
            {
				System.out.println( "Skipping fixed tile " + idx2 );
            }

			// TODO: Export / Import master sprint mesh vertices no calculated  individually per tile (v1, v2).
			corr_data.add(new CorrespondenceSpec(
					mipmapLevel,
					imageUrl1,
					imageUrl2,
					pm12_strip));
			
			corr_data.add(new CorrespondenceSpec(
					mipmapLevel,
					imageUrl2,
					imageUrl1,
					pm21_strip));
		}
		
		if ( corr_data.size() > 0 ) {
			try {
				Writer writer = new FileWriter(params.targetPath);
		        //Gson gson = new GsonBuilder().create();
		        Gson gson = new GsonBuilder().setPrettyPrinting().create();
		        gson.toJson(corr_data, writer);
		        writer.close();
		    }
			catch ( final IOException e )
			{
				System.err.println( "Error writing JSON file: " + params.targetPath );
				e.printStackTrace( System.err );
			}
		}
	}
	
}
