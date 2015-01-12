/**
 * Title: Force Field X.
 *
 * Description: Force Field X - Software for Molecular Biophysics.
 *
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2014.
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
 */
package ffx.potential.utils;

import ffx.potential.ForceFieldEnergy;
import ffx.potential.MolecularAssembly;
import ffx.potential.Utilities;
import ffx.potential.parameters.ForceField;
import ffx.potential.parsers.BiojavaFilter;
import ffx.potential.parsers.FileOpener;
import ffx.potential.parsers.ForceFieldFilter;
import ffx.utilities.Keyword;
import java.io.File;
import static java.lang.String.format;
import java.util.List;
import java.util.logging.Logger;
import org.apache.commons.configuration.CompositeConfiguration;
import org.apache.commons.io.FilenameUtils;
import org.biojava.bio.structure.Structure;

/**
 * The PotentialsDataConverter class describes a Runnable object which converts
 * some data structure to Force Field X MolecularAssembly(s).
 * 
 * @author Jacob M. Litman
 * @author Michael J. Schnieders
 */
public class PotentialsDataConverter implements FileOpener {
    
    private static final Logger logger = Logger.getLogger(PotentialsDataConverter.class.getName());
    
    private final File file; // Used to generate the Properties.
    private final Object dataStructure;
    // Should in the future allow a list of data structures.
    private final Utilities.DataType dataType;
    private List<MolecularAssembly> assemblies;
    private MolecularAssembly activeAssembly; // Presently, will just be the first element of assemblies.
    private List<CompositeConfiguration> propertyList;
    private CompositeConfiguration activeProperties;
    
    
    public PotentialsDataConverter (Object data) {
        this(data, null);
    }
    
    public PotentialsDataConverter (Object data, File file) {
        if (data instanceof Structure) {
            this.dataStructure = data;
            this.dataType = Utilities.DataType.BIOJAVA;
            if (file == null) {
                this.file = getBiojavaFile((Structure) data);
            } else {
                this.file = file;
            }
        } else {
            this.dataStructure = null;
            this.dataType = Utilities.DataType.UNK;
            this.file = file;
        }
        // Can add other else-ifs for other data structure types.
    }
    
    /**
     * Attempt to get the file the Structure was loaded from.
     * @param structure A Biojava structure
     * @return pdbcode.pdb, name.pdb, or null
     */
    public static File getBiojavaFile(Structure structure) {
        String filename = structure.getPDBCode() + ".pdb";
        File file = new File(filename);
        if (!file.exists() || file.isDirectory()) {
            filename = structure.getName() + ".pdb";
            file = new File(filename);
            if (!file.exists() || file.isDirectory()) {
                file = null;
            }
        }
        return file;
    }

    /**
     * Converts the data structure to MolecularAssembly(s).
     */
    @Override
    public void run() {
        if (dataStructure == null || dataType.equals(Utilities.DataType.UNK)) {
            throw new IllegalArgumentException("Object passed was not recognized.");
        }
        switch (dataType) {
            case BIOJAVA:
                Structure struct = (Structure) dataStructure;
                String name = struct.getPDBCode();
                CompositeConfiguration properties = Keyword.loadProperties(file);
                MolecularAssembly assembly = new MolecularAssembly(name);
                assembly.setFile(file);
                ForceFieldFilter forceFieldFilter = new ForceFieldFilter(properties);
                ForceField forceField = forceFieldFilter.parse();
                assembly.setForceField(forceFieldFilter.parse());
                
                BiojavaFilter filter = new BiojavaFilter(struct, assembly, forceField, properties);
                if (filter.convert()) {
                    Utilities.biochemistry(assembly, filter.getAtomList());
                    assembly.finalize(true, forceField);
                    ForceFieldEnergy energy = new ForceFieldEnergy(assembly);
                    assembly.setPotential(energy);
                    assemblies.add(assembly);
                    propertyList.add(properties);
                    
                    List<Character> altLocs = filter.getAltLocs();
                    if (altLocs.size() > 1 || altLocs.get(0) != ' ') {
                        StringBuilder altLocString = new StringBuilder("\n Alternate locations found [ ");
                        for (Character c : altLocs) {
                            // Do not report the root conformer.
                            if (c == ' ') {
                                continue;
                            }
                            altLocString.append(format("(%s) ", c));
                        }
                        altLocString.append("]\n");
                        logger.info(altLocString.toString());
                    }

                    /**
                     * Alternate conformers may have different chemistry, so
                     * they each need to be their own MolecularAssembly.
                     */
                    for (Character c : altLocs) {
                        if (c.equals(' ') || c.equals('A')) {
                            continue;
                        }
                        MolecularAssembly newAssembly = new MolecularAssembly(name);
                        newAssembly.setForceField(assembly.getForceField());
                        filter.setAltID(assembly, c);
                        filter.clearSegIDs();
                        if (filter.convert()) {
                            String fileName = assembly.getFile().getAbsolutePath();
                            newAssembly.setName(FilenameUtils.getBaseName(fileName) + " " + c);
                            energy = new ForceFieldEnergy(newAssembly);
                            newAssembly.setPotential(energy);
                            assemblies.add(newAssembly);
                            properties.addConfiguration(properties);
                        }
                    }
                } else {
                    logger.warning(String.format(" Failed to convert structure %s", dataStructure.toString()));
                }
                activeAssembly = assembly;
                activeProperties = properties;
                break;
            case UNK:
            default:
                throw new IllegalArgumentException("Object passed was not recognized.");
        }
    }

    /**
     * Returns the first MolecularAssembly created by the run() function.
     *
     * @return A MolecularAssembly
     */
    @Override
    public MolecularAssembly getAssembly() {
        return activeAssembly;
    }
    
    /**
     * Returns the i'th MolecularAssembly
     * @param i Index
     * @return The i'th MolecularAssembly
     */
    public MolecularAssembly getAssembly(int i) {
        return assemblies.get(i);
    }

    /**
     * Returns all MolecularAssembly objects created by this converter.
     *
     * @return Array of MolecularAssemblys
     */
    @Override
    public MolecularAssembly[] getAllAssemblies() {
        return assemblies.toArray(new MolecularAssembly[assemblies.size()]);
    }

    /**
     * Returns the properties associated with the first MolecularAssembly.
     *
     * @return Active properties
     */
    @Override
    public CompositeConfiguration getProperties() {
        return activeProperties;
    }
    
    /**
     * Returns the properties associated with the i'th MolecularAssembly
     * @param i
     * @return 
     */
    public CompositeConfiguration getProperties(int i) {
        return propertyList.get(i);
    }

    /**
     * Returns the properties of all MolecularAssembly objects created by this
     * converter.
     * 
     * @return Array of all properties
     */
    @Override
    public CompositeConfiguration[] getAllProperties() {
        return propertyList.toArray(new CompositeConfiguration[propertyList.size()]);
    }
    
}