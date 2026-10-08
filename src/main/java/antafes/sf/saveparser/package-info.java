/*
 * This file is part of SFManager.
 *
 * SFManager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SFManager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with SFManager. If not, see <http://www.gnu.org/licenses/>.
 *
 * @package SFManager
 * @author Marian Pollzien <map@wafriv.de>
 * @copyright (c) 2026, Marian Pollzien
 * @license https://www.gnu.org/licenses/lgpl.html LGPLv3
 */

/**
 * Read-only parsing of Satisfactory save files.
 *
 * <p>The public {@link SaveParserService} is implemented by the stateless Spring
 * {@link SaveParser} service. The
 * package supports the Satisfactory save formats represented by the application's regression
 * fixture and modern save header versions, but deliberately provides no save mutation or writing
 * API. Legacy version 36 saves expose world and object-header structure without interpreting
 * their older object payload layouts as modern properties.</p>
 */
package antafes.sf.saveparser;
