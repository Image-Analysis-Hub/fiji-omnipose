package fiji.plugin.appose.omnipose;

import static fiji.plugin.appose.ApposeUtils.addROIs;
import static fiji.plugin.appose.ApposeUtils.clearOutsideRoi;
import static fiji.plugin.appose.ApposeUtils.getAxisInfo;
import static fiji.plugin.appose.ApposeUtils.rawWraps;
import static fiji.plugin.appose.ApposeUtils.transferCalibration;

import java.awt.Color;

import org.scijava.ui.config.fiji.ConfigFijiPluginPreviewable;
import org.scijava.ui.config.visitors.gui.FrameBuilder.ConfigFrame;

import fiji.plugin.appose.listeners.FijiApposeProgressListener;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import net.imagej.ImgPlus;
import net.imglib2.appose.util.AxisInfo;
import net.imglib2.omnipose.OmniposeOutput;
import net.imglib2.omnipose.OmniposeParameters;
import net.imglib2.omnipose.OmniposeRunner;

public class OmniposePlugin extends ConfigFijiPluginPreviewable< OmniposeConfig >
{

	private OmniposeRunner runner;

	private String previousTorchVersion;

	private FijiApposeProgressListener listener;

	protected OmniposeParameters toParams( final OmniposeConfig config )
	{
		final String selection = config.builtinOrCustom().getSelection().getKey();
		final boolean isBuiltin = selection.equals( "BUILTIN_MODEL" );

		final OmniposeParameters params = OmniposeParameters.builder()
				.model( isBuiltin ? config.builtinModel().getValue() : null )
				.customModel( isBuiltin ? null : config.customModel().getValue() )
				.diameter( config.diameter().getValue() )
				.channels( config.channel().getValue(), 0 )
				.minSize( config.minSize().getValue() )
				.normalize( config.normalize().getValue() )
				.resample( true ) // Must be true here, as we expect the output
									// to have the same size as the input.
				.maskThreshold( config.maskThreshold().getValue() )
				.flowThreshold( config.flowThreshold().getValue() )
				.tileOverlap( config.tileOverlap().getValue() )
				.computeFlows( config.exportFlows().getValue() )
				.do3D( config.do3D().getValue() )
				.stitchThreshold( config.stitchThreshold().getValue() )
				.nIter( config.nIter().getValue() )
				.torchVersion( config.torchVersion().getValue() )
				.useGpu( config.useGpu().getValue() )
				.build();
		return params;
	}

	@Override
	protected OmniposeConfig createConfig( final ImagePlus imp )
	{
		final int nChannels = imp.getNChannels();
		final double pixelSize = imp.getCalibration().pixelWidth;
		final String units = imp.getCalibration().getUnit();
		return new OmniposeConfig( nChannels, pixelSize, units );
	}

	@SuppressWarnings( "unchecked" )
	@Override
	protected void process( final ImagePlus imp, final int tOrigin )
	{
		progress.clear();
		final long startTime = System.currentTimeMillis();
		// Store the roi in the source image for later.
		final Roi roi = imp.getRoi();

		try
		{
			// Convert config to Cellpose parameters.
			final OmniposeParameters params = toParams( config );

			// Create the runner if it doesn't exist or recreate it.
			if ( runner == null || !params.torchVersion.equals( previousTorchVersion ) )
			{
				listener = new FijiApposeProgressListener( progress, config.getName() );
				runner = OmniposeRunner.create( listener, params.torchVersion );
				runner.init();
				previousTorchVersion = params.torchVersion;
			}

			// Wrap input.
			Roi initialRoi = imp.getRoi();
			if ( initialRoi != null )
				initialRoi = ( Roi ) initialRoi.clone();
			@SuppressWarnings( "rawtypes" )
			final ImgPlus input = rawWraps( imp );
			final AxisInfo inputAxes = getAxisInfo( input );

			// Exec.
			runner.setInput( input, inputAxes );
			runner.run( params );
			final OmniposeOutput< ? > oo = runner.getOutput();

			final long endTime1 = System.currentTimeMillis();
			progress.message( String.format( "Omnipose done in %.1f seconds. Postprocessing outputs...",
					( endTime1 - startTime ) / 1000.0 ) );

			// Clear outside of the ROI, if any.
			clearOutsideRoi( oo.labels, initialRoi );
			if ( oo.flows != null )
				clearOutsideRoi( oo.flows, initialRoi );

			// To ImagePlus.
			final ImagePlus[] outputs = Omnipose.toImp( oo );

			// Reposition the outputs.
			for ( final ImagePlus out : outputs )
				transferCalibration( imp, out, initialRoi );

			// Unwrap the outputs and show them.
			final ImagePlus labels = outputs[ 0 ];
			if ( config.exportROIs().getValue() && imp.getNSlices() == 1 )
			{
				final boolean multipleChannels = imp.getNChannels() > 1;
				addROIs( labels, config.getName(), Color.YELLOW, tOrigin, multipleChannels );
				RoiManager.getInstance2().runCommand( "Show All" );
			}
			if ( config.exportLabels().getValue() )
				labels.show();
			if ( config.exportFlows().getValue() && outputs.length > 1 )
			{
				final ImagePlus flows = outputs[ 1 ];
				flows.show();
			}
		}
		catch ( final Exception e )
		{
			IJ.handleException( e );
		}
		finally
		{
			// Restore the roi in the source image.
			imp.setRoi( roi );
			progress.clear();
			final long endTime2 = System.currentTimeMillis();
			progress.message( String.format( "Done in %.1f seconds.", ( endTime2 - startTime ) / 1000.0 ) );
		}
	}

	/**
	 * Adds a hook the UI, so that we close the {@link #runner} when the UI is
	 * closed.
	 */
	@Override
	protected ConfigFrame showUI()
	{
		final ConfigFrame ui = super.showUI();
		ui.addWindowListener( new java.awt.event.WindowAdapter()
		{
			@Override
			public void windowClosed( final java.awt.event.WindowEvent e )
			{
				if ( runner != null )
				{
					runner.close();
					runner = null;
					listener.close();
					listener = null;
				}
			}
		} );
		return ui;
	}
}
