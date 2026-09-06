package fiji.plugin.appose.omnipose;

import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;

public class OmniposeFijiPluginDemo
{
	@SuppressWarnings( "unchecked" )
	public static void main( final String[] args )
	{
		setLF();
		try
		{
			ImageJ.main( args );

			// Since this is a demo in the src folder, we need to register the
			// plugin manually, as it won't be picked up by the usual plugin
			// discovery mechanism.
			ij.Menus.getCommands().put( "Omnipose", "fiji.plugin.appose.omnipose.OmniposePlugin" );

			// Switch on macro recorder.
//			new Recorder();

//			final String filePath = "../imglib2-omnipose/samples/20230331_washed_XY1.ome-1_stabilized_cropped-t61.tif";
			final String filePath = "../imglib2-omnipose/samples/20230331_washed_XY1.ome-1_stabilized_cropped.tif";
			final ImagePlus imp = IJ.openImage( filePath );
			imp.show();

			new OmniposePluginFrame().run( "" );
//			new OmniposePlugin().run( "" );
		}
		catch ( final Exception e )
		{
			e.printStackTrace();
		}
	}

	static void setLF()
	{
		try
		{
			UIManager.setLookAndFeel( UIManager.getSystemLookAndFeelClassName() );
		}
		catch ( ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException e )
		{
			e.printStackTrace();
		}

	}
}
