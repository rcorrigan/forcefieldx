// TEST LAMBDA GRADIENT

import org.apache.commons.io.FilenameUtils;

import ffx.numerics.Potential;
import ffx.potential.bonded.Atom;
import ffx.potential.bonded.MolecularAssembly;
import ffx.potential.ForceFieldEnergy;

// Name of the file (PDB or XYZ).
String filename = args[0];
if (filename == null) {
   println("\n Usage: ffxc testLambdaGradient filename ligandStart ligandStop lambda");
   return; 
}

int ligandStart = 1;
if (args.size() > 1) {
    ligandStart = Integer.parseInt(args[1]);
}

int ligandStop = ligandStart;
if (args.size() > 2) {
    ligandStop = Integer.parseInt(args[2]);
}

double initialLambda = 1.0;
if (args.size() > 3) {
    initialLambda = Double.parseDouble(args[3]);
}

// Things below this line normally do not need to be changed.
// ===============================================================================================

println("\n Testing lambda gradient for " + filename);

System.setProperty("lambdaterm","true");
System.setProperty("ligand-start", Integer.toString(ligandStart));
System.setProperty("ligand-stop", Integer.toString(ligandStop));

open(filename);

ForceFieldEnergy energy = active.getPotentialEnergy();

Atom[] atoms = active.getAtomArray();
int n = atoms.length;

// Apply the ligand atom selection
for (int i = ligandStart; i <= ligandStop; i++) {
    Atom ai = atoms[i - 1];
    ai.setApplyLambda(true);
}

// Compute the Lambda = 1.0 energy.
double lambda = 1.0;
energy.setLambda(lambda);
double e1 = energy.energy(false, true);
println(String.format(" E(1):      %20.8f.", e1));

// Compute the Lambda = 0.0 energy.
lambda = 0.0;
energy.setLambda(lambda);
double e0 = energy.energy(false, true);
println(String.format(" E(0):      %20.8f.", e0));
println(String.format(" E(1)-E(0): %20.8f.", e1-e0));

// Finite-difference step size.
double step = 1.0e-5;
   
// Test Lambda gradient in the neighborhood of the lambda variable.
for (int j=0; j<3; j++) {
   lambda = initialLambda - 0.01 + 0.01 * j;

   if (lambda - step < 0.0) {
       continue;
   }
   if (lambda + step > 1.0) {
       continue;
   }
   
   println " Current lambda value " + lambda;
    
   energy.setLambda(lambda);

   // Calculate the energy, dE/dX, dE/dLambda, d2E/dLambda2 and dE/dLambda/dX
   double e = energy.energy(true, false);

   // Analytic dEdLambda and d2E/dLambda2
   double dEdLambda = energy.getdEdL();
   double dE2dLambda2 = energy.getd2EdL2();

   // Analytic dEdLambdadX
   double[] dEdLdX = new double[n * 3];
   energy.getdEdXdL(dEdLdX);

   // Calculate the finite-difference dEdLambda, d2EdLambda2 and dEdLambdadX
   double[][] dedx = new double[2][n * 3];

   energy.setLambda(lambda + step);
   double lp = energy.energy(true, false);
   double dedlp = energy.getdEdL();
   energy.getGradients(dedx[0]);

   energy.setLambda(lambda - step);
   double lm = energy.energy(true, false);
   double dedlm = energy.getdEdL();
   energy.getGradients(dedx[1]);

   double dedl = (lp - lm) / (2.0 * step);
   double d2edl2 = (dedlp - dedlm) / (2.0 * step);

   println(String.format(" Analytic dE/dL:   %15.8f", dEdLambda));
   println(String.format(" Numeric  dE/dL:   %15.8f\n", dedl));

   println(String.format(" Analytic d2E/dL2: %15.8f", dE2dLambda2));
   println(String.format(" Numeric  d2E/dL2: %15.8f\n", d2edl2));

   for (int i = ligandStart - 1; i < ligandStop; i++) {
       println(" dE/dX/dL for Ligand Atom " + (i + 1));
       int index = i * 3;
       double dX = (dedx[0][index] - dedx[1][index]) / (2.0 * step);
       index++;
       double dY = (dedx[0][index] - dedx[1][index]) / (2.0 * step);
       index++;
       double dZ = (dedx[0][index] - dedx[1][index]) / (2.0 * step);
       println(String.format(" Analytic: (%15.8f, %15.8f, %15.8f)", 
            dEdLdX[i*3],dEdLdX[i*3+1],dEdLdX[i*3+2]));
       println(String.format(" Numeric:  (%15.8f, %15.8f, %15.8f)", dX,dY,dZ));
   }
}