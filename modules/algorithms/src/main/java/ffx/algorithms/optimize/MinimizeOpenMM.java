/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ffx.algorithms.optimize;

import java.util.logging.Logger;
import static java.lang.Double.isInfinite;
import static java.lang.Double.isNaN;
import static java.lang.String.format;

import com.sun.jna.ptr.PointerByReference;

import static org.apache.commons.math3.util.FastMath.sqrt;

import edu.uiowa.jopenmm.OpenMM_Vec3;
import static edu.uiowa.jopenmm.AmoebaOpenMMLibrary.OpenMM_KcalPerKJ;
import static edu.uiowa.jopenmm.AmoebaOpenMMLibrary.OpenMM_NmPerAngstrom;
import static edu.uiowa.jopenmm.OpenMMLibrary.OpenMM_Context_getState;
import static edu.uiowa.jopenmm.OpenMMLibrary.OpenMM_LocalEnergyMinimizer_minimize;
import static edu.uiowa.jopenmm.OpenMMLibrary.OpenMM_State_DataType.OpenMM_State_Energy;
import static edu.uiowa.jopenmm.OpenMMLibrary.OpenMM_State_DataType.OpenMM_State_Forces;
import static edu.uiowa.jopenmm.OpenMMLibrary.OpenMM_State_DataType.OpenMM_State_Positions;
import static edu.uiowa.jopenmm.OpenMMLibrary.OpenMM_State_destroy;
import static edu.uiowa.jopenmm.OpenMMLibrary.OpenMM_State_getForces;
import static edu.uiowa.jopenmm.OpenMMLibrary.OpenMM_State_getPositions;
import static edu.uiowa.jopenmm.OpenMMLibrary.OpenMM_State_getPotentialEnergy;
import static edu.uiowa.jopenmm.OpenMMLibrary.OpenMM_Vec3Array_get;

import ffx.algorithms.AlgorithmListener;
import ffx.numerics.Potential;
import ffx.numerics.optimization.LineSearch;
import ffx.potential.ForceFieldEnergy;
import ffx.potential.ForceFieldEnergyOpenMM;
import ffx.potential.MolecularAssembly;

/**
 * OpenMM accelerated L-BFGS minimization.
 *
 * @author Hernan Bernabe
 */
public class MinimizeOpenMM extends Minimize {

    public static final Logger logger = Logger.getLogger(MinimizeOpenMM.class.getName());

    public MinimizeOpenMM(MolecularAssembly molecularAssembly) {
        super(molecularAssembly, molecularAssembly.getPotentialEnergy(), null);
    }

    public MinimizeOpenMM(MolecularAssembly molecularAssembly, ForceFieldEnergyOpenMM forceFieldEnergyOpenMM) {
        super(molecularAssembly, forceFieldEnergyOpenMM, null);
    }

    public MinimizeOpenMM(MolecularAssembly molecularAssembly, ForceFieldEnergyOpenMM forceFieldEnergyOpenMM,
                          AlgorithmListener algorithmListener) {
        super(molecularAssembly, forceFieldEnergyOpenMM, algorithmListener);
    }


    /**
     * Note the OpenMM L-BFGS minimizer does not accept the parameter "m"
     * for the number of previous steps used to estimate the Hessian.
     *
     * @param m             The number of previous steps used to estimate the Hessian (ignored).
     * @param eps           The convergence criteria.
     * @param maxIterations The maximum number of iterations.
     * @return The potential.
     */
    @Override
    public Potential minimize(int m, double eps, int maxIterations) {
        return minimize(eps, maxIterations);
    }

    /**
     * <p>
     * minimize</p>
     *
     * @param eps           The convergence criteria.
     * @param maxIterations The maximum number of iterations.
     * @return a {@link ffx.numerics.Potential} object.
     */
    @Override
    public Potential minimize(double eps, int maxIterations) {
        ForceFieldEnergy forceFieldEnergy = molecularAssembly.getPotentialEnergy();

        if (forceFieldEnergy instanceof ForceFieldEnergyOpenMM) {
            time = -System.nanoTime();
            ForceFieldEnergyOpenMM forceFieldEnergyOpenMM = (ForceFieldEnergyOpenMM) forceFieldEnergy;
            forceFieldEnergyOpenMM.getCoordinates(x);
            forceFieldEnergyOpenMM.setOpenMMPositions(x, x.length);

            // Run the OpenMM minimization.
            PointerByReference context = forceFieldEnergyOpenMM.getContext();
            OpenMM_LocalEnergyMinimizer_minimize(context, eps / (OpenMM_NmPerAngstrom * OpenMM_KcalPerKJ), maxIterations);

            // Get the minimized coordinates, forces and potential energy back from OpenMM.
            int infoMask = OpenMM_State_Positions + OpenMM_State_Energy + OpenMM_State_Forces;
            PointerByReference state = OpenMM_Context_getState(context, infoMask, forceFieldEnergyOpenMM.enforcePBC);
            energy = OpenMM_State_getPotentialEnergy(state) * OpenMM_KcalPerKJ;
            PointerByReference positions = OpenMM_State_getPositions(state);
            PointerByReference forces = OpenMM_State_getForces(state);

            // Load updated coordinate position.
            int numParticles = n / 3;
            forceFieldEnergyOpenMM.getOpenMMPositions(positions, numParticles, x);

            // Compute the RMS gradient.
            int index = 0;
            double totalForce = 0;
            for (int i = 0; i < numParticles; i++) {
                OpenMM_Vec3 forceOpenMM = OpenMM_Vec3Array_get(forces, i);
                double fx = forceOpenMM.x * OpenMM_NmPerAngstrom * OpenMM_KcalPerKJ;
                double fy = forceOpenMM.y * OpenMM_NmPerAngstrom * OpenMM_KcalPerKJ;
                double fz = forceOpenMM.z * OpenMM_NmPerAngstrom * OpenMM_KcalPerKJ;
                totalForce += fx * fx + fy * fy + fz * fz;
                if (isNaN(totalForce) || isInfinite(totalForce)) {
                    String message = format(" The gradient of variable %d is %8.3f.", i, totalForce);
                    logger.warning(message);
                }
                grad[index++] = -fx;
                grad[index++] = -fy;
                grad[index++] = -fz;
            }
            rmsGradient = sqrt(totalForce / n);

            // Clean up.
            OpenMM_State_destroy(state);

            double[] ffxGrad = new double[n];
            double ffxEnergy = forceFieldEnergy.energyAndGradient(x, ffxGrad);
            double grmsFFX = 0.0;
            for (int i = 0; i < n; i++) {
                double gi = ffxGrad[i];
                if (isNaN(gi) || isInfinite(gi)) {
                    String message = format(" The gradient of variable %d is %8.3f.", i, gi);
                    logger.warning(message);
                }
                grmsFFX += gi * gi;
            }
            grmsFFX = sqrt(grmsFFX / n);

            time += System.nanoTime();
            logger.info(format(" Convergence criteria for OpenMM %12.6f vs. FFX %12.6f (kcal/mol/A).",
                    rmsGradient, grmsFFX));
            logger.info(format(" Final energy for         OpenMM %12.6f vs. FFX %12.6f (kcal/mol) in %8.3f (sec).",
                    energy, ffxEnergy, time * 1.0e-9));
        }

        if (algorithmListener != null) {
            algorithmListener.algorithmUpdate(molecularAssembly);
        }

        return forceFieldEnergy;
    }

    /**
     * MinimizeOpenMM does not currently support the OptimizationListener interface.
     *
     * @since 1.0
     */
    @Override
    public boolean optimizationUpdate(int iteration, int functionEvaluations, double rmsGradient,
                                      double rmsCoordinateChange, double energy, double energyChange,
                                      double angle, LineSearch.LineSearchResult lineSearchResult) {
        logger.warning(" MinimizeOpenMM does not support updates at each optimization step.");
        return false;
    }


}