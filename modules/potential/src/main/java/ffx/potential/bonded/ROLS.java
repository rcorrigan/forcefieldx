/**
 * Title: Force Field X.
 *
 * Description: Force Field X - Software for Molecular Biophysics.
 *
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2015.
 *
 * This file is part of Force Field X.
 *
 * Force Field X is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 as published by
 * the Free Software Foundation.
 *
 * Force Field X is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place, Suite 330, Boston, MA 02111-1307 USA
 *
 * Linking this library statically or dynamically with other modules is making a
 * combined work based on this library. Thus, the terms and conditions of the
 * GNU General Public License cover the whole combination.
 *
 * As a special exception, the copyright holders of this library give you
 * permission to link this library with independent modules to produce an
 * executable, regardless of the license terms of these independent modules, and
 * to copy and distribute the resulting executable under terms of your choice,
 * provided that you also meet, for each linked independent module, the terms
 * and conditions of the license of that module. An independent module is a
 * module which is not derived from or based on this library. If you modify this
 * library, you may extend this exception to your version of the library, but
 * you are not obligated to do so. If you do not wish to do so, delete this
 * exception statement from your version.
 */
package ffx.potential.bonded;

import java.util.ArrayList;
import java.util.List;

import javax.media.j3d.BranchGroup;
import javax.media.j3d.Canvas3D;
import javax.media.j3d.J3DGraphics2D;
import javax.media.j3d.Material;
import javax.media.j3d.Node;
import javax.vecmath.Color3f;

import ffx.potential.bonded.RendererCache.ColorModel;
import ffx.potential.bonded.RendererCache.ViewModel;

/**
 * The ROLS Interace defines "Recursive Over Length Scales" (ROLS) Methods.
 *
 * @author Michael J. Schnieders
 *
 */
public interface ROLS {

    /**
     * Constant <code>MaxLengthScale=5</code>
     */
    public static final int MaxLengthScale = 5;
    /**
     * Constant <code>LengthScale=MaxLengthScale</code>
     */
    public static int LengthScale = MaxLengthScale;

    /**
     * <p>
     * drawLabel</p>
     *
     * @param graphics a {@link javax.media.j3d.Canvas3D} object.
     * @param g2d a {@link javax.media.j3d.J3DGraphics2D} object.
     * @param node a {@link javax.media.j3d.Node} object.
     */
    public void drawLabel(Canvas3D graphics, J3DGraphics2D g2d, Node node);

    /**
     * <p>
     * getCenter</p>
     *
     * @param w a boolean.
     * @return an array of double.
     */
    public double[] getCenter(boolean w);

    /**
     * <p>
     * getList</p>
     *
     * @param c a {@link java.lang.Class} object.
     * @param nodes a {@link java.util.ArrayList} object.
     * @return a {@link java.util.ArrayList} object.
     */
    public ArrayList<ROLS> getList(Class c, ArrayList<ROLS> nodes);

    /**
     * <p>
     * getMSCount</p>
     *
     * @param c a {@link java.lang.Class} object.
     * @param count a long.
     * @return a long.
     */
    public long getMSCount(Class c, long count);

    /**
     * <p>
     * getMSNode</p>
     *
     * @param c a {@link java.lang.Class} object.
     * @return a {@link ffx.potential.bonded.ROLS} object.
     */
    public ROLS getMSNode(Class c);

    /**
     * <p>
     * getMW</p>
     *
     * @return a double.
     */
    public double getMW();

    /**
     * <p>
     * setColor</p>
     *
     * @param colorModel a {@link ffx.potential.bonded.RendererCache.ColorModel}
     * object.
     * @param color a {@link javax.vecmath.Color3f} object.
     * @param mat a {@link javax.media.j3d.Material} object.
     */
    public void setColor(ColorModel colorModel, Color3f color, Material mat);

    /**
     * <p>
     * setView</p>
     *
     * @param viewModel a {@link ffx.potential.bonded.RendererCache.ViewModel}
     * object.
     * @param newShapes a {@link java.util.List} object.
     */
    public void setView(ViewModel viewModel, List<BranchGroup> newShapes);

    /**
     * <p>
     * update</p>
     */
    public void update();
}
