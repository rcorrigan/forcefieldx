/**
 * Title: Force Field X.
 *
 * Description: Force Field X - Software for Molecular Biophysics.
 *
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2016.
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
package ffx.autoparm;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import org.openscience.cdk.atomtype.CDKAtomTypeMatcher;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.exception.InvalidSmilesException;
import org.openscience.cdk.fragment.ExhaustiveFragmenter;
import org.openscience.cdk.fragment.MurckoFragmenter;
import org.openscience.cdk.interfaces.IAtom;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IAtomType;
import org.openscience.cdk.io.SDFWriter;
import org.openscience.cdk.io.iterator.IteratingSDFReader;
import org.openscience.cdk.isomorphism.Pattern;
import org.openscience.cdk.isomorphism.VentoFoggia;
import org.openscience.cdk.modeling.builder3d.ModelBuilder3D;
import org.openscience.cdk.silent.SilentChemObjectBuilder;
import org.openscience.cdk.smiles.SmilesParser;
import org.openscience.cdk.tools.CDKHydrogenAdder;
import org.openscience.cdk.tools.manipulator.AtomContainerManipulator;
import org.openscience.cdk.tools.manipulator.AtomTypeManipulator;

/**
 * Splits large molecules into fragments for PolType Input: full molecule SDF
 * Output: individual fragment SDFs
 *
 * @author Rae Ann Corrigan
 */
public class Fragmenter {

    private final static Logger logger = Logger.getLogger(Fragmenter.class.getName());

    protected String filename;
    protected String smilesString;

    public Fragmenter(String filename, String smilesString) {
        this.filename = filename;
        this.smilesString = smilesString;
    }

    private enum FIELD {
        XLogP,
        ALogP,
        ALogp2,
        AMR,
        SMILES_Kekule,
        SMILES_Aromatic
    }

    private static final int SIZE = 30;

    public void readSDF() throws FileNotFoundException, IOException {
        File file = new File(filename);
        BufferedReader read = null;

        try {
            FileReader fileReader = new FileReader(file);
            read = new BufferedReader(fileReader);
        } catch (IOException e) {
            e.printStackTrace();
        }

        IteratingSDFReader reader = null;

        try {

            reader = new IteratingSDFReader(read, SilentChemObjectBuilder.getInstance());
            while (reader.hasNext()) {

                IAtomContainer molecule = reader.next();

                try {
                    AtomContainerManipulator.percieveAtomTypesAndConfigureAtoms(molecule);
                    CDKHydrogenAdder.getInstance(SilentChemObjectBuilder.getInstance()).addImplicitHydrogens(molecule);

                    //Fragmentation call
                    fragment(molecule);

                } catch (Exception x) {
                    System.err.println("*");
                    System.out.println(x);
                }
            }
        } catch (Exception x) {
            x.printStackTrace();
        } finally {
            try {
                reader.close();
            } catch (Exception x) {
            }
        }
    }

    String[] fArray = new String[]{"For SMILES"};
    String[] frame = new String[]{"For Frameworks"};
    String[] rings = new String[]{"For Ring Systems"};
    String[] eArray = new String[]{"For Exh Fragments"};
    String[] eatArray = new String[]{"For testing for eaten fragments"};
    String[] rhArray = new String[]{"For removed-hydrogen SMILES"};
    int numSubstructures = 0;
    List<String> toRemove = new ArrayList<>();
    List<String> finalList = new ArrayList<>();
    List<IAtomContainer> rhList = new ArrayList<>();
    List<Integer> indicelist = new ArrayList<>();
    List<String> finallist = new ArrayList<>();
    int[][] map = null;
    int[][] mapfinal = null;

    protected void fragment(IAtomContainer molecule) throws Exception {
        //MurckoFragmenter implimentation
        MurckoFragmenter murk = new MurckoFragmenter();
        murk.generateFragments(molecule);
        System.out.println("MURCKO FRAGMENTS");
        fArray = murk.getFragments();

        System.out.println(Arrays.toString(fArray));

        //ExhaustiveFragmenter implimentation
        ExhaustiveFragmenter exh = new ExhaustiveFragmenter();
        exh.setMinimumFragmentSize(20);
        exh.generateFragments(molecule);
        System.out.println("\nEXHAUSTIVE FRAGMENTS");
        eArray = exh.getFragments();
        eatArray = exh.getFragments();
        rhArray = exh.getFragments();
        int orig = eatArray.length;

        System.out.println(Arrays.toString(eArray) + "\n");

        //checking for "eaten" fragments
        //remove hydrogens for more accurate substructure checking
        //for (int s = 0; s < rhArray.length; s++)
        for (String rhArray1 : rhArray) {
            //convert each array entry (SMILES) to IAtomContainer
            IAtomContainer molec = null;
            try {
                SmilesParser smp = new SmilesParser(SilentChemObjectBuilder.getInstance());
                molec = smp.parseSmiles(rhArray1);
            } catch (InvalidSmilesException ise) {
                System.out.println(ise.toString());
            }
            //remove hydrogens using CDK AtomContainerManipulator.removeHydrogens(IAtomContainer)
            try {
                AtomContainerManipulator.removeHydrogens(molec);
            } catch (Exception e) {
                e.printStackTrace();
            }
            rhList.add(molec);
        }

        //check for substructures and collect indicies to take out entires from full-H array
        for (int t = 0; t < rhList.size(); t++) {
            IAtomContainer query = rhList.get(t);
            Pattern pattern = VentoFoggia.findSubstructure(query);

            for (int u = 0; u < rhList.size(); u++) {
                IAtomContainer tester = rhList.get(u);

                //is "Query" is a substructure of "Tester"
                //makes sure query and tester aren't the same molecule and that
                //     query is smaller than tester (substructures have to be
                //     smaller than the main structure)
                if (pattern.matches(tester) && (tester != query) && (tester.getAtomCount() > query.getAtomCount()) && (tester.getAtomCount() < SIZE)) {
                    indicelist.add(t);
                }
            }
        }

        for (int v = 0; v < rhList.size(); v++) {
            if (!indicelist.contains(v)) {
                finallist.add(eatArray[v]);
            } else {
                numSubstructures++;
            }
        }

        //Make final list of non-substructures into an array to pass on
        String[] finalArray = new String[finallist.size()];

        for (int n = 0; n < finallist.size(); n++) {
            finalArray[n] = finallist.get(n);
        }

        System.out.print("Substructures removed: ");
        System.out.println(numSubstructures);

        System.out.println("Orig length: " + orig);
        System.out.println("Final length: " + finalArray.length + "\n");

        //map creation
        //T[][] 2Darray = new T[numRows][numCols];
        map = new int[molecule.getAtomCount()][finalArray.length];
        //initialize map array to 0
        for (int o = 0; o < map.length; o++) {
            for (int p = 0; p < map[o].length; p++) {
                map[o][p] = 0;
            }
        }
        //System.out.println("Number of Rows: "+molecule.getAtomCount());
        //System.out.println("Number of Cols: "+finalArray.length+"\n");

        //fill first col with full molecule atom numbers
        for (int q = 0; q < map.length; q++) {
            map[q][0] = (q + 1);
        }

        String full = smilesString;
        System.out.println("fullSmiles: " + full + "\n");

        //write SMILES file
        //pass fArray to smilesToObject to convert Murcko fragments to SDF
        //pass eArray to smilesToObject to convert Exhaustive fragments to SDF
        //pass finalArray to smilesToObject to convert non-substructure fragments to SDF
        smilesToObject(finalArray, full);

        //basic idea: if map column is empty, cut it out of final map before printing
        //implementation: create new final map and only include the filled in columns
        mapfinal = new int[molecule.getAtomCount()][fragcounter];

        for (int row = 0; row < map.length; row++) {
            for (int col = 0; col < fragcounter; col++) {
                mapfinal[row][col] = map[row][col];
            }
        }

        System.out.println("Map: ");
        for (int pc = 0; pc < mapfinal.length; pc++) {
            for (int pc2 = 0; pc2 < mapfinal[pc].length; pc2++) {
                System.out.print(mapfinal[pc][pc2] + "   ");
            }
            System.out.println();
        }

    }

    protected void smilesToObject(String[] smilesArr, String fullsmi) throws Exception {

        String fullsm = fullsmi;
        for (int i = 0; i < smilesArr.length; i++) {
            String content = smilesArr[i];
            //entry number in SMILES array to be used later in SDF writing
            int num = i;
            //System.out.println("\nPassing SMILES string to doConversion\n");
            doConversion(content, num, fullsm);
        }

    }

    int fragcounter = 1;

    protected void doConversion(String smi, int num, String fullsmi) throws Exception {
        IAtomContainer mol = null;
        IAtomContainer full = null;
        List<IAtom> toFullTest = new ArrayList<>();
        List<IAtom> toFragTest = new ArrayList<>();
        //entry number in SMILES array to be used later in SDF writing
        int number = num;

        //Parse SMILES for full drug
        try {
            SmilesParser fullp = new SmilesParser(SilentChemObjectBuilder.getInstance());
            full = fullp.parseSmiles(fullsmi);
        } catch (InvalidSmilesException ise) {
            System.out.println(ise.toString());
        }

        //Parse SMILES for fragment
        try {
            SmilesParser sp = new SmilesParser(SilentChemObjectBuilder.getInstance());
            mol = sp.parseSmiles(smi);
        } catch (InvalidSmilesException ise) {
            System.out.println(ise.toString());
        }

        //AtomTypeMatcher for full drug
        try {
            CDKAtomTypeMatcher match = CDKAtomTypeMatcher.getInstance(full.getBuilder());
            for (IAtom fatom : full.atoms()) {
                IAtomType ftype = match.findMatchingAtomType(full, fatom);
                AtomTypeManipulator.configure(fatom, ftype);
            }
        } catch (Exception e) {
            System.out.println(e);
        }

        //AtomTypeMatcher for frag
        try {
            CDKAtomTypeMatcher matcher = CDKAtomTypeMatcher.getInstance(mol.getBuilder());
            for (IAtom atom : mol.atoms()) {
                IAtomType type = matcher.findMatchingAtomType(mol, atom);
                AtomTypeManipulator.configure(atom, type);
            }
        } catch (Exception e) {
            System.out.println(e);
        }

        //CDKHydrogenAdder for full drug
        try {
            CDKHydrogenAdder fha = CDKHydrogenAdder.getInstance(full.getBuilder());
            fha.addImplicitHydrogens(full);
            AtomContainerManipulator.convertImplicitToExplicitHydrogens(full);
        } catch (CDKException e) {
            System.out.println(e);
        }

        //CDKHydrogenAdder for fragment
        try {
            CDKHydrogenAdder ha = CDKHydrogenAdder.getInstance(mol.getBuilder());
            ha.addImplicitHydrogens(mol);
            AtomContainerManipulator.convertImplicitToExplicitHydrogens(mol);
        } catch (CDKException e) {
            System.out.println(e);
        }

        //Builds 3D model of fragment molecule
        ModelBuilder3D mb3d;
        mb3d = ModelBuilder3D.getInstance(SilentChemObjectBuilder.getInstance());
        IAtomContainer molecule = null;
        molecule = mb3d.generate3DCoordinates(mol, false);

        //Fragmenting checks
        //30 atoms or less
        if (molecule.getAtomCount() < SIZE) {

            //"eaten" fragments checked for already
            //writeSDF
            File fragsdf = writeSDF(molecule, number);
            //writeXYZ(molecule, number);
            int fraglen = mol.getAtomCount();
            int fulllen = full.getAtomCount();
            map(fragsdf, number, fraglen, fulllen);

            fragcounter++;
        }

    }

    protected File writeSDF(IAtomContainer iAtomContainer, int n) throws Exception {

        String fileBegin = "fragment";
        String fileEnd = Integer.toString(n);
        String dirName = fileBegin.concat(fileEnd);

        // Make a subdirectory.
        File dir = new File(dirName);
        if (!dir.exists()) {
            dir.mkdir();
        }

        String fragName = dirName.concat(File.separator).concat(dirName.concat(".sdf"));
        logger.info(String.format(" Writing %s", fragName));

        BufferedWriter bufferedWriter = null;
        FileWriter fileWriter = null;
        SDFWriter sdfWriter = null;
        File sdfFromSMILES = new File(fragName);

        try {
            fileWriter = new FileWriter(sdfFromSMILES.getAbsoluteFile());
            bufferedWriter = new BufferedWriter(fileWriter);
            sdfWriter = new SDFWriter();
            sdfWriter.setWriter(bufferedWriter);
            sdfWriter.write(iAtomContainer);
        } catch (IOException e) {
            logger.warning(e.toString());
        } finally {
            if (fileWriter != null) {
                fileWriter.close();
            }
            if (bufferedWriter != null) {
                bufferedWriter.close();
            }
            if (sdfWriter != null) {
                sdfWriter.close();
            }
        }

        return sdfFromSMILES;
    }

    protected void map(File sdfFromSMILES, int col, int fragsize, int fullsize) throws FileNotFoundException {

        String[] fragAtoms = new String[fragsize];
        String[] fullAtoms = new String[fullsize];

        //read full SDF file
        try {
            File file = new File(filename);
            FileReader fullfr = new FileReader(file);
            BufferedReader fullbr = new BufferedReader(fullfr);
            String line;
            int counter = 0;
            while ((line = fullbr.readLine()) != null) {
                String test = line;

                //find atoms
                if (test.contains(" 0  0  0  0  0  0  0  0  0  0  0  0")) {

                    String atom;
                    String str31;
                    String str32;

                    //sets atom values for fullAtoms array
                    char char31 = test.charAt(31);
                    if (test.charAt(32) != ' ') {
                        char char32 = test.charAt(32);
                        str32 = Character.toString(char32);
                        str31 = Character.toString(char31);

                        atom = str31.concat(str32);
                        fullAtoms[counter] = atom;
                    } else {
                        str31 = Character.toString(char31);

                        atom = str31;
                        fullAtoms[counter] = atom;
                    }
                    counter++;
                    //System.out.println("Char at position 31: "+char31);
                }
            }

        } catch (IOException e) {
            System.out.println(e);
        }

        //read frag SDF file
        try {
            FileReader fragfr = new FileReader(sdfFromSMILES);
            BufferedReader fragbr = new BufferedReader(fragfr);
            String line;
            int counter = 0;
            while ((line = fragbr.readLine()) != null) {
                String test = line;

                //find atoms
                if (test.contains(" 0  0  0  0  0  0  0  0  0  0  0  0")) {

                    String atom;
                    String str31;
                    String str32;

                    //sets atom values for fragAtoms array
                    char char31 = test.charAt(31);
                    if (test.charAt(32) != ' ') {
                        char char32 = test.charAt(32);
                        str32 = Character.toString(char32);
                        str31 = Character.toString(char31);

                        atom = str31.concat(str32);
                        fragAtoms[counter] = atom;
                    } else {
                        str31 = Character.toString(char31);

                        atom = str31;
                        fragAtoms[counter] = atom;
                    }
                    counter++;
                    //System.out.println("Char at position 31: "+char31);
                }
            }

        } catch (IOException e) {
            System.out.println(e);
        }

        //created arrays of atoms for full molecule and fragment
        //now create arrays of bonds for full molecule and fragment
        int fullrows = 0;
        int fragrows = 0;

        try {
            File file = new File(filename);
            FileReader fullfr = new FileReader(file);
            BufferedReader fullbr = new BufferedReader(fullfr);
            String line;

            while ((line = fullbr.readLine()) != null) {
                String test = line;

                //determine how many bonds full molecule has
                if (test.contains(" 1  0  0  0  0") || test.contains(" 2  0  0  0  0")) {
                    fullrows++;
                }
            }

        } catch (IOException e) {
            System.out.println(e);
        }
        try {
            FileReader fragfr = new FileReader(sdfFromSMILES);
            BufferedReader fragbr = new BufferedReader(fragfr);
            String line;

            while ((line = fragbr.readLine()) != null) {
                String test = line;

                //determine how many bonds frag molecule has
                if (test.contains(" 1  0  0  0  0") || test.contains(" 2  0  0  0  0")) {
                    fragrows++;
                }
            }

        } catch (IOException e) {
            System.out.println(e);
        }

        //set #cols to 3 instead of 2 if decide to use sod
        String[][] fragbonds = new String[fragrows][2];
        String[][] fullbonds = new String[fullrows][2];

        //fill arrays of bonds for full molecule
        try {
            File file = new File(filename);
            FileReader fullfr = new FileReader(file);
            BufferedReader fullbr = new BufferedReader(fullfr);
            String line;
            int counter = 0;
            while ((line = fullbr.readLine()) != null) {
                String test = line;

                if (test.contains(" 1  0  0  0  0") || test.contains(" 2  0  0  0  0")) {
                    //variables for bond components
                    //atoms involved and if the bond is single (s) or double (d)
                    String atom1;
                    String atom2;
                    String a1;
                    String a2;
                    String sod;

                    //set atom1
                    char char1 = test.charAt(1);
                    char char2 = test.charAt(2);
                    if (test.charAt(1) != ' ') {
                        a2 = Character.toString(char2);
                        a1 = Character.toString(char1);

                        atom1 = a1.concat(a2);
                        fullbonds[counter][0] = atom1;
                    } else {
                        a2 = Character.toString(char2);

                        atom1 = a2;
                        fullbonds[counter][0] = atom1;
                    }

                    //set atom2
                    char char4 = test.charAt(4);
                    char char5 = test.charAt(5);
                    if (test.charAt(4) != ' ') {
                        a2 = Character.toString(char5);
                        a1 = Character.toString(char4);

                        atom2 = a1.concat(a2);
                        fullbonds[counter][1] = atom2;
                    } else {
                        a2 = Character.toString(char5);

                        atom2 = a2;
                        fullbonds[counter][1] = atom2;
                    }

                    //set sod (maybe; increase #cols in bonds array to 3 if so)
                    /*char char8 = test.charAt(8);
                    sod = Character.toString(char8);
                    fullbonds[counter][2] = sod;*/
                    counter++;
                }
            }

        } catch (IOException e) {
            System.out.println(e);
        }

        //fill arrays of bonds for frag molecule
        try {
            FileReader fragfr = new FileReader(sdfFromSMILES);
            BufferedReader fragbr = new BufferedReader(fragfr);
            String line;
            int counter = 0;
            while ((line = fragbr.readLine()) != null) {
                String test = line;

                if (test.contains(" 1  0  0  0  0") || test.contains(" 2  0  0  0  0")) {
                    //variables for bond components
                    //atoms involved and if the bond is single (s) or double (d)
                    String atom1;
                    String atom2;
                    String a1;
                    String a2;
                    String sod;

                    //set atom1
                    char char1 = test.charAt(1);
                    char char2 = test.charAt(2);
                    if (test.charAt(1) != ' ') {
                        a2 = Character.toString(char2);
                        a1 = Character.toString(char1);

                        atom1 = a1.concat(a2);
                        fragbonds[counter][0] = atom1;
                    } else {
                        a2 = Character.toString(char2);

                        atom1 = a2;
                        fragbonds[counter][0] = atom1;
                    }

                    //set atom2
                    char char4 = test.charAt(4);
                    char char5 = test.charAt(5);
                    if (test.charAt(4) != ' ') {
                        a2 = Character.toString(char5);
                        a1 = Character.toString(char4);

                        atom2 = a1.concat(a2);
                        fragbonds[counter][1] = atom2;
                    } else {
                        a2 = Character.toString(char5);

                        atom2 = a2;
                        fragbonds[counter][1] = atom2;
                    }

                    //set sod (maybe; increase #cols in bonds array to 3 if so)
                    /*char char8 = test.charAt(8);
                    sod = Character.toString(char8);
                    fragbonds[counter][2] = sod;*/
                    counter++;
                }
            }

        } catch (IOException e) {
            System.out.println(e);
        }

        //Print tests for atom and bond arrays
        System.out.println("fullAtoms: " + Arrays.toString(fullAtoms));
        System.out.println("fragAtoms: " + Arrays.toString(fragAtoms));

        System.out.println("Fullbonds: ");
        for (int pc = 0; pc < fullbonds.length; pc++) {
            for (int pc2 = 0; pc2 < fullbonds[pc].length; pc2++) {
                System.out.print(fullbonds[pc][pc2] + "   ");
            }
            System.out.println();
        }

        System.out.println("Fragbonds: ");
        for (int pc = 0; pc < fragbonds.length; pc++) {
            for (int pc2 = 0; pc2 < fragbonds[pc].length; pc2++) {
                System.out.print(fragbonds[pc][pc2] + "   ");
            }
            System.out.println();
        }

        //now mapping!
        try {

            int numFragBonds = 0;
            int numFullBonds = 0;

            //System.out.println("fragbonds.length: "+fragbonds.length+"\n"+"fullbonds.length: "+fullbonds.length+"\n");
            for (int fullit = 0; fullit < fullAtoms.length; fullit++) {

                List<Integer> fullBondsToCheck = new ArrayList<>();

                String testFullAtom = fullAtoms[fullit];
                int finterval = fullit + 1;
                String fullInterval = Integer.toString(finterval);
                //System.out.println("TESTFULLATOM                     = "+testFullAtom+"");
                //System.out.println("FULLINTERVAL                     = " +fullInterval+"\n"+"---------------------------------------");

                for (int fragit = 0; fragit < fragAtoms.length; fragit++) {

                    List<Integer> bondsToCheck = new ArrayList<>();

                    String testFragAtom = fragAtoms[fragit];
                    int interval = fragit + 1;
                    String fragInterval = Integer.toString(interval);

                    //if atoms are of the same element
                    if (testFullAtom.equals(testFragAtom)) {
                        //look at bonds
                        /*System.out.println("          ATOMS MATCHED!");
                   System.out.println("testFragAtom = "+testFragAtom);
                   System.out.println("fragInterval = "+fragInterval+"\n");*/

                        for (int bondC = 0; bondC < fragbonds.length; bondC++) {
                            if (fragbonds[bondC][0].equals(fragInterval) || fragbonds[bondC][1].equals(fragInterval)) {
                                //System.out.println("Found fragbond");
                                if (!bondsToCheck.contains(bondC)) {
                                    //System.out.println("Added bond number "+bondC+" to the frag bonds to check\n");
                                    bondsToCheck.add(bondC);
                                    numFragBonds++;
                                }
                            }
                        } //checked fragbonds

                        for (int bondCo = 0; bondCo < fullbonds.length; bondCo++) {
                            if (fullbonds[bondCo][0].equals(fullInterval) || fullbonds[bondCo][1].equals(fullInterval)) {

                                if (!fullBondsToCheck.contains(bondCo)) {
                                    //System.out.println("Found fullbond");
                                    //System.out.println("Added bond number "+bondCo+" to the full bonds to check\n");
                                    fullBondsToCheck.add(bondCo);
                                    numFullBonds++;
                                } else {
                                    //System.out.println("Found full bond "+ bondCo+", but it was already in the check array\n");
                                }
                            }
                        } //checked fullbonds

                        //System.out.println("bondsToCheck: "+Arrays.toString(bondsToCheck.toArray())+"\n");
                        //System.out.println("fullBondsToCheck: "+Arrays.toString(fullBondsToCheck.toArray())+"\n");
                        //match bonding
                        //create full and frag bond ArraysLists
                        List<String> fragBondList = new ArrayList<>();
                        List<String> fullBondList = new ArrayList<>();

                        //location of the atoms in fragAtoms array
                        String atom1num = null;
                        String atom2num = null;
                        int atom1Int = 0;
                        int atom2Int = 0;

                        //actual atoms in fragAtoms array
                        String atom1;
                        String atom2;

                        //atoms of bond string
                        String atomsOfBond;

                        //fill fragBondList
                        for (int i = 0; i < bondsToCheck.size(); i++) {

                            //bond = the row in Fragbonds
                            int bond = bondsToCheck.get(i);
                            atom1num = fragbonds[bond][0];
                            atom2num = fragbonds[bond][1];

                            atom1Int = Integer.parseInt(atom1num);
                            atom2Int = Integer.parseInt(atom2num);

                            //determine which atoms are part of the bond of interest
                            atom1 = fragAtoms[atom1Int - 1];
                            atom2 = fragAtoms[atom2Int - 1];

                            atomsOfBond = atom1.concat(atom2);
                            fragBondList.add(atomsOfBond);
                        }

                        /*//START HERE FOR FRAGMENT EXTRA
                        //Additional bonds for deeper checking
                        int numExtraAtoms = bondsToCheck.size();
                        //string arrays for the extra bonded atoms; no atom should have more than
                        //four bonded atoms (in the drugs I've seen), so none will have more than
                        //three extra atoms (exB as in extra bonds)
                        List<String> exB1 = new ArrayList<>();
                        List<String> exB2 = new ArrayList<>();
                        List<String> exB3 = new ArrayList<>();
                        List<String> exB4 = new ArrayList<>();
                        List<Integer> extraBondsToCheck = new ArrayList<>();

                        for (int extraCount = 0; extraCount < numExtraAtoms; extraCount++) {

                            //set atom number for deeper checking
                            // each atom that frag test atom is bonded to
                            // reset for each fragment bondToCheck
                            int atomN = 0;
                            if (!atom1num.equals(fragInterval)) {
                                int frA = Integer.parseInt(atom1num);
                                atomN = frA;
                            } else {
                                int frA = Integer.parseInt(atom2num);
                                atomN = frA;
                            }

                            //find the bonds atomN is part of, not including the original bondToCheck
                            for (int bondC = 0; bondC < fragbonds.length; bondC++) {
                                if (fragbonds[bondC][0].equals(atomN) || fragbonds[bondC][1].equals(atomN)
                                        && !fragbonds[bondC][0].equals(fragInterval) && !fragbonds[bondC][1].equals(fragInterval)) {
                                    if (!extraBondsToCheck.contains(bondC)) {

                                        extraBondsToCheck.add(bondC);

                                    }
                                }
                            } //checked fragbonds

                            //name and number of atom to add to exB's
                            //get the atom that isn't "atomN" in each extra bond
                            int atomA = 0;
                            String atomSA;

                            String atomNumberS = Integer.toString(atomN);

                            for (int extraFragIt = 0; extraFragIt < extraBondsToCheck.size(); extraFragIt++) {

                                //bond number within fragbonds
                                int ebtcB = extraBondsToCheck.get(extraFragIt);

                                //gets number of the atom of interest; bound to bonded atom of original test atom
                                if(!fragbonds[ebtcB][0].equals(atomNumberS)){
                                    atomA = Integer.parseInt(fragbonds[ebtcB][0]);
                                } else{
                                    atomA = Integer.parseInt(fragbonds[ebtcB][1]);
                                }

                                //gets atomic symbol of the atom of interset; bound to the bonded atom of the original test atom
                                atomSA = fragAtoms[atomA];

                                switch (extraCount) {
                                    case 0:
                                        exB1.add(atomSA);
                                        break;
                                    case 1:
                                        exB2.add(atomSA);
                                        break;
                                    case 2:
                                        exB3.add(atomSA);
                                        break;
                                //end switch
                                    case 3:
                                        exB3.add(atomSA);
                                        break;
                                    default:
                                        break;
                                }
                            }

                        }

                        //END EXTRA FOR FRAGMENT*/
                        //location of the atoms in fullAtoms array
                        String fatom1num;
                        String fatom2num;
                        int fatom1Int;
                        int fatom2Int;

                        //actual atoms in fullAtoms array
                        String fatom1;
                        String fatom2;

                        //atoms of bond string
                        String fatomsOfBond;

                        //fill fullBondList
                        for (int i = 0; i < fullBondsToCheck.size(); i++) {

                            //bond = the row in fullbonds
                            int bond = fullBondsToCheck.get(i);
                            fatom1num = fullbonds[bond][0];
                            fatom2num = fullbonds[bond][1];

                            fatom1Int = Integer.parseInt(fatom1num);
                            fatom2Int = Integer.parseInt(fatom2num);

                            //determine which atoms are part of the bond of interest
                            fatom1 = fullAtoms[fatom1Int - 1];
                            fatom2 = fullAtoms[fatom2Int - 1];

                            fatomsOfBond = fatom1.concat(fatom2);
                            fullBondList.add(fatomsOfBond);

                        }

                        //print fragBondList and fullBondList as a test
                        /*System.out.println("--------------------");
                   System.out.println("fragBondList: ");
                   System.out.println(Arrays.toString(fragBondList.toArray())+"\n");
                   System.out.println("fullBondList: "+Arrays.toString(fullBondList.toArray())+"\n");*/
                        //iteration through bond lists
                        boolean bondfound = false;
                        List<Integer> alreadyPresent = new ArrayList<>();
                        int bondCount = 0;

                        if (fullBondList.size() == fragBondList.size()) {  //makes sure tested atoms have same number of bonds
                            while (!bondfound) {
                                int bondCOrig = bondCount;
                                boolean check = true;
                                for (int i = 0; i < fullBondList.size(); i++) {
                                    //System.out.println("          Mapping\n");
                                    //split bond entry into characters
                                    String bond = fullBondList.get(i);
                                    char firstc = bond.charAt(0);
                                    String first = Character.toString(firstc);
                                    char secondc = bond.charAt(1);
                                    String second = Character.toString(secondc);
                                    String bondOp = second.concat(first);

                                    //for CCl bond
                                    if (bond.length() == 3) {
                                        char firstch = bond.charAt(0);
                                        String ch1 = Character.toString(firstch);
                                        char secondch = bond.charAt(1);
                                        String ch2 = Character.toString(secondch);
                                        char thirdch = bond.charAt(2);
                                        String ch3 = Character.toString(thirdch);
                                        String at1 = ch1.concat(ch2);

                                        bondOp = ch3.concat(at1);

                                    }

                                    //iterate thru fragBondList to see if entry contains first and second
                                    //while(!bondfound2){
                                    for (int j = 0; j < fragBondList.size(); j++) {
                                        String test = fragBondList.get(j);
                                        // System.out.println("Test: "+test+"\nbond: "+bond+"\nbondOp: "+bondOp);

                                        //if the tested frag bond involves the same atoms as the full bond
                                        //previously if(test.contains(first) && test.contains(second)){
                                        if (test.equals(bond) || test.equals(bondOp)) {
                                            //remove the bond from frag bonds list
                                            fragBondList.remove(j);
                                            bondCount++;

                                            /*System.out.println("fullBondList.size() = "+fullBondList.size());
                                            System.out.println("Bond count = "+bondCount);
                                            System.out.println("j = "+j);
                                            System.out.println("fragBondList.size() = "+fragBondList.size());
                                            System.out.println("fragBondList current = "+Arrays.toString(fragBondList.toArray())+"\n");*/
                                            if (bondCount == fullBondList.size() && check) {
                                                //bondfound
                                                bondfound = true;

                                                for (int k = 0; k < fullAtoms.length; k++) {
                                                    alreadyPresent.add(map[k][fragcounter]);
                                                }

                                                //map it!
                                                if (map[finterval - 1][fragcounter] == 0 && !alreadyPresent.contains(interval)) {
                                                    map[finterval - 1][fragcounter] = interval;
                                                    //alreadyPresent.add(interval);
                                                    //System.out.println("Mapped Successfully\n");
                                                }
                                            }
                                        } else {
                                            //System.out.println("     Bond didn't match\n");
                                        }
                                    }

                                    //System.out.println("bondCOrig: "+bondCOrig+"\nbondCount: "+bondCount+"\n");
                                    //if one of the full bonds was not found in frag array
                                    if (bondCOrig == bondCount) {
                                        check = false;
                                        //System.out.println("Check is FALSE\n");
                                    }

                                    /*if(fragBondList.size() > 0){
                                        if(!fragBondList.get(0).contains("H")){
                                            check = false;
                                            System.out.println("Check is FALSE\n");
                                        }
                                    }*/
                                    //}
                                }

                                bondfound = true;
                            } //end while(!bondfound)
                        }
                    }

                }
            }

        } catch (ArrayIndexOutOfBoundsException a) {
            a.printStackTrace();
        }
    }

} //end Fragmenter

//Code for reference
//XYZ writer
/*protected void writeXYZ(IAtomContainer sm, int n) throws Exception{

        int j = n;
        String fileBegin = "fragment.xyz_";
        String fileEnd = Integer.toString(j);
        String filename = fileBegin.concat(fileEnd);
        System.out.println("XYZ name: "+filename+"\n");

        XYZWriter xyzWrite = new XYZWriter();

        try{
            File xyzFromSMILES = new File(filename);
            FileWriter filew = new FileWriter(xyzFromSMILES.getAbsoluteFile());
            BufferedWriter out = new BufferedWriter(filew);

            xyzWrite.setWriter(out);
            xyzWrite.writeMolecule(sm);
            out.close();
        } catch (IOException e){
            e.printStackTrace();
        } finally{
            xyzWrite.close();
        }
    }*/
//Things I've tried for mapping
//Tried using AtomTypeMatcher and affiliated functions
/*for (int mapC = 0; mapC < full.getAtomCount(); mapC++) {
                CDKAtomTypeMatcher mapmatch = CDKAtomTypeMatcher.getInstance(full.getBuilder());
                IAtom testFullAtom = null;
                //assigns an atom from the full drug to search for in the fragment
                testFullAtom = full.getAtom(mapC);
                IAtomType testFullType = mapmatch.findMatchingAtomType(full, testFullAtom);
                //fills ArrayList of atoms connected to the testFullAtom
                //toFullTest = full.getConnectedAtomsList(testFullAtom);
                //get IAtomTypes for toFullTest list; compare to IAtomTypes of toFragTest
                //List<IAtomType> toFullTypes = new ArrayList<>();

                //fills IAtomTypes array, toFullTypes
                /*for(int typeC = 0; typeC < toFullTest.size(); typeC++){
                    toFullTypes.add(mapmatch.findMatchingAtomType(full, toFullTest.get(typeC)));
                }
                System.out.println("toFullTypes size: "+toFullTypes.size());*/
 /*for (int mapC2 = 0; mapC2 < mol.getAtomCount(); mapC2++) {
                    CDKAtomTypeMatcher fragmapmatch = CDKAtomTypeMatcher.getInstance(mol.getBuilder());
                    IAtom testFragAtom = null;
                    //assigns an atom from the fragment to compare to the atom assigned from the full drug
                    testFragAtom = mol.getAtom(mapC2);
                    IAtomType testFragType = fragmapmatch.findMatchingAtomType(mol, testFragAtom);
                    //fills ArrayList of atoms connected to testFragAtom
                    //toFragTest = mol.getConnectedAtomsList(testFragAtom);
                    //get IAtomTypes for toFragTest list; compare to IAtomTypes of toFullTest
                    //List<IAtomType> toFragTypes = new ArrayList<>();

                    //fills IAtomTypes array, to FragTypes
                    /*for(int typeC2 = 0; typeC2 < toFragTest.size(); typeC2++){
                        toFragTypes.add(fragmapmatch.findMatchingAtomType(mol, toFragTest.get(typeC2)));
                    }
                    System.out.println("toFragTypes size: "+toFragTypes.size());

                    int maxsize = 0;
                    //sets counter variable to the maximum length of connected atom array
                    if(toFragTypes.size() > toFullTypes.size()){
                        maxsize = toFragTypes.size();
                    } else{
                        maxsize = toFullTypes.size();
                    }

                    int matchingConnectedAtoms = 1;

                    //if the connected atoms don't match, then set the test variable to 0 (false)
                    for(int conCount = 0; conCount < maxsize; conCount++){
                        if(toFullTypes.get(conCount) != toFragTypes.get(conCount)){
                            matchingConnectedAtoms = 0;
                        }
                    }*/
//if the atoms match
//System.out.println("Entering if types/atoms match if\n");
/*if (testFullType == testFragType) {
                        //System.out.println("Atoms match!");
                        int indice = full.getAtomNumber(testFullAtom);
                        int fnum = mol.getAtomNumber(testFragAtom);

                        //enter number of matching fragAtom into mapping array
                        //in same row as matched testFullAtom
                        //in fragcounter col
                        //checks to see if the map col for the corresponding fragment
                        //already contains the fnum
                        int mapcheck = 0;
                        for (int colCount = 0; colCount < full.getAtomCount(); colCount++) {
                            if (map[colCount][fragcounter] == fnum) {
                                mapcheck = 1;
                            }
                        }

                        //if the fnum isn't already in the frag column
                        if (mapcheck == 0) {

                            //and there's not something already in the designated indice
                            if (map[indice][fragcounter] == 0) {
                                //set the indice to the found fnum
                                map[indice][fragcounter] = fnum;
                            }
                        }
                    }
                }
            }*/
//Tried using AtomMappingTools (would output * before making the AtomMappingTools element)
//Entered structures NOT aligned
/*System.out.println("Making fragmap HashMap\n");
            Map<Integer, Integer> fragmap = new HashMap<>();
            System.out.println("Finished making fragmap HashMap\n");

            try {
                //AtomMappingTools mapMaker = (AtomMappingTools) AtomMappingTools.mapAtomsOfAlignedStructures(full, mol, fragmap);
                fragmap = AtomMappingTools.mapAtomsOfAlignedStructures(full, mol, fragmap);
                System.out.println("Finished making mapMaker\n");
            } catch (CDKException e) {
                System.out.println(e);
                e.printStackTrace();
            }
            //fills map array according to mapped list; only fills frags
            System.out.println("Filling map array\n");
            for (int mapcounter = 0; mapcounter < full.getAtomCount(); mapcounter++) {
                map[mapcounter][fragcounter] = fragmap.get(mapcounter);
            }
            System.out.println("Finished filling map array\n");*/
//Tried using DefaultRGraphAtomMatcher; atoms aren't the same enough to be matched between mol and full
/*boolean bondMatch = false;
            int row = 0;
            for(int dgm = 0; dgm < mol.getAtomCount(); dgm++){
                DefaultRGraphAtomMatcher drgam = new DefaultRGraphAtomMatcher(full, mol.getAtom(dgm),bondMatch);

                if(drgam.matches(full, mol.getAtom(dgm))){
                    //determine row

                    //returns -1 if atom not found (which apparently it's not...
                    //row = full.getAtomNumber(mol.getAtom(dgm));
                    System.out.println("Row: "+row);
                    for(int count = 0; count < full.getAtomCount(); count++){
                        if(full.getAtom(count) == mol.getAtom(dgm) && (map[count][fragcounter] == 0)){

                            row = count;
                        }
                    }
                    map[row][fragcounter] = dgm;
                }
            }*/

 /* Tried KabschAlignment; needs IAtomContainers or IAtom arrays of the same length
            //Atom arrays
            IAtom[] fullAtoms = new IAtom[full.getAtomCount()];
            IAtom[] molAtoms = new IAtom[mol.getAtomCount()];

            //manually "getAtoms" by iterating thru both IAtomContainers and making arrays of IAtoms
            for(int fullAC = 0; fullAC < full.getAtomCount(); fullAC++){
                fullAtoms[fullAC] = full.getAtom(fullAC);
            }
            int molAC;
            for(molAC = 0; molAC < mol.getAtomCount(); molAC++){
                molAtoms[molAC] = mol.getAtom(molAC);
            }

            System.out.println("Made IAtom array\n");
            System.out.println(Arrays.toString(fullAtoms));
            System.out.println();
            System.out.println(Arrays.toString(molAtoms));
            System.out.println();

            KabschAlignment sa = new KabschAlignment(fullAtoms, molAtoms);
            System.out.println("KabschAlignment\n");
            sa.align();*/
