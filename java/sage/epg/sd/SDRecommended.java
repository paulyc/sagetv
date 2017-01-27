/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage.epg.sd;

import sage.Agent;
import sage.Airing;
import sage.DBObject;
import sage.MediaFile;
import sage.Sage;
import sage.Seeker;
import sage.SeriesInfo;
import sage.Show;
import sage.TVEditorial;
import sage.Wizard;
import sage.epg.sd.json.images.SDImage;
import sage.epg.sd.json.images.SDProgramImages;
import sage.epg.sd.json.programs.SDProgram;
import sage.epg.sd.json.programs.SDRecommendation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SDRecommended
{
  private static final int IR_LIMIT = 1000;
  private static final boolean USE_CURRENT_RECORDINGS = true;
  private static final boolean EXCLUDE_DONT_LIKE = true;

  /**
   * Returns the assumed best image for this specific editorial candidate.
   */
  public static String getBestEditorialImage(SDEditorial editorial) throws IOException, SDException
  {
    Show show = editorial.getAiring().getShow();
    String url = null;

    SDProgram program = editorial.getProgram();
    if (program != null && program.hasImageArtwork())
    {
      SDProgramImages programImages[] = SDRipper.ensureSession().getProgramImages(new String[] { program.getProgramID() });
      if (programImages.length > 0 && programImages[0].getCode() == 0)
      {
        SDImage images[] = programImages[0].getImages();
        if (images.length > 0)
        {
          SDImage bestImage = images[0];
          int desiredCategory = show.isMovie() ? SDImage.CAT_BOX_ART : SDImage.CAT_BANNER_L1;
          boolean desiredSize = false;
          for (SDImage image : images)
          {
            boolean portrait = image.isPortrait();
            byte size = image.getSizeByte();
            byte category = image.getCategoryByte();

            // Get the last image that fits our criteria to try to ensure that this image will be
            // different from the one that might been seen in the guide.
            if (portrait && size == SDImage.SIZE_MD && category == desiredCategory)
            {
              desiredSize = true;
              bestImage = image;
            }
            else if (portrait && category == desiredCategory && !desiredSize)
            {
              bestImage = image;
            }
          }
          url = bestImage.getUri();
        }
      }
    }
    if (url == null)
    {
      if (show.isMovie())
        url = show.getAnyImageUrl(0, true);
      else
        url = show.getImageUrlForIndex(0, true);
    }
    if (url == null && !show.isMovie())
    {
      SDImage image = editorial.getProgram().getEpisodeImage();
      url = image != null ? image.getUri() : null;

      if (url == null)
      {
        try
        {
          String externalID = show.getExternalID();
          int legacySeriesID = Integer.parseInt(externalID.substring(2, externalID.length() - 6));
          SeriesInfo seriesInfo = Wizard.getInstance().getSeriesInfoForLegacySeriesID(legacySeriesID);
          if (seriesInfo != null)
          {
            url = seriesInfo.getImageURL(0, true);
          }
        } catch (NumberFormatException e) {}
      }
    }

    return url == null ? "" : url;
  }

  /**
   * Reduce the number of editorial candidates to a specific number by occurrence.
   * <p/>
   * This will also retrieve program details and add them to the objects because it's more efficient
   * to do it this way since we are already iterating through the editorials.
   */
  public static void reduceByWeight(List<SDEditorial> editorials, int limit) throws IOException, SDException
  {
    Collections.sort(editorials, SDEditorial.WEIGHT_COMPARATOR);
    limit = Math.min(editorials.size(), limit);
    List<String> query = new ArrayList<>(limit);
    for (int i = 0; i < editorials.size(); i++)
    {
      SDEditorial editorial = editorials.get(i);
      String programId = editorial.getAiring().getShow().getExternalID();

      // If we are already over the limit, try to limit the imports to things we actually have
      // artwork to display. This also helps ensures that the show to be displayed is one that
      // someone cared enough to provide artwork and that often also means that the show is
      // fairly well received.
      if (editorials.size() > limit)
      {
        SDProgram program = editorial.getProgram();
        if (program != null && !program.hasImageArtwork())
        {
          editorials.remove(i--);
          continue;
        }
      }

      query.add(programId);
      if (query.size() >= limit)
      {
        while (i < editorials.size())
        {
          editorials.remove(i);
        }
        break;
      }
    }

    SDProgram programs[] = SDRipper.ensureSession().getPrograms(query);
    for (int i = 0; i < programs.length; i++)
    {
      SDEditorial editorial = editorials.get(i);
      editorial.setProgram(programs[i]);
    }

    if (Sage.DBG) System.out.println("SDEPG Recommended: " + editorials.toString());
  }

  public static List<SDEditorial> getUsable(List<SDEditorial> editorials)
  {
    int alreadyExists = 0;
    List<SDEditorial> returnValues = new ArrayList<>();
    Agent favorites[] = Wizard.getInstance().getFavorites();
    TVEditorial currentEditorials[] = Wizard.getInstance().getEditorials();
    for (SDEditorial editorial : editorials)
    {
      SDRecommendation recommendation = editorial.getRecommendation();
      String title = recommendation.getTitle120();
      if (title == null || title.length() == 0)
        continue;
      String programId = recommendation.getProgramId();
      if (programId == null || programId.length() == 0)
        continue;

      // Don't recommend movies that we have already seen or don't exist in the Wizard.
      if (programId.startsWith("MV"))
      {
        Show show;
        if ((show = Wizard.getInstance().getShowForExternalID(programId)) == null || show.isWatched())
          continue;
        String newId = SDUtils.fromProgramToSageTV(programId);
        if (newId.length() == 12 &&
          (show = Wizard.getInstance().getShowForExternalID(programId)) == null || show.isWatched())
          continue;
      }

      // Don't recommend shows that are clearly already favorites. We might get a show that changed
      // titles or would have been recorded due to other rules, but we aren't too concerned about
      // that.
      boolean skip = false;
      for (Agent favorite : favorites)
      {
        if (favorite.getTitle().equals(title))
        {
          skip = true;
          break;
        }
      }
      if (skip)
        continue;
      // Sometimes we will find a different external ID for a show that's already in the editorials.
      // As that editorial expires, if the show is still a suggestion, it will be re-added with a
      // different external ID.
      for (TVEditorial currentEditorial : currentEditorials)
      {
        if (currentEditorial.getTitle().equals(title))
        {
          alreadyExists++;
          skip = true;
          break;
        }
      }
      if (skip)
        continue;

      // Check if we could't record this show if we wanted to.
      Show shows[] = Wizard.getInstance().getShowsForExternalIDPrefix(programId);
      String newID = SDUtils.fromProgramToSageTV(programId);
      if (newID.length() == 12)
      {
        Show moreShows[] = Wizard.getInstance().getShowsForExternalIDPrefix(newID);
        int copyIndex = shows.length;
        shows = Arrays.copyOf(shows, shows.length + moreShows.length);
        System.arraycopy(moreShows, 0, shows, copyIndex, moreShows.length);
      }
      if (programId.startsWith("SH") && programId.length() == 14)
      {
        Show moreShows[] = Wizard.getInstance().getShowsForExternalIDPrefix("EP" + programId.substring(2, 10));
        int copyIndex = shows.length;
        shows = Arrays.copyOf(shows, shows.length + moreShows.length);
        System.arraycopy(moreShows, 0, shows, copyIndex, moreShows.length);
        if (newID.length() == 12)
        {
          moreShows = Wizard.getInstance().getShowsForExternalIDPrefix("EP" + newID.substring(2, 8));
          copyIndex = shows.length;
          shows = Arrays.copyOf(shows, shows.length + moreShows.length);
          System.arraycopy(moreShows, 0, shows, copyIndex, moreShows.length);
        }
      }
      long now = Sage.time() + Sage.MILLIS_PER_DAY;
      Airing selectedAiring = null;
      boolean isHD = false;
      for (Show show : shows)
      {
        Airing airings[] = Wizard.getInstance().getAirings(show, now);
        if (airings.length > 0)
        {
          for (Airing airing : airings)
          {
            // Don't recommend shows we have watched before.
            if (airing.isWatched())
            {
              selectedAiring = null;
              break;
            }

            if (!isHD)
            {
              isHD = airing.isHDTV();
              selectedAiring = selectedAiring == null ? airing : (isHD ? airing : selectedAiring);
            }
            else
            {
              break;
            }
          }
        }
      }
      if (selectedAiring == null)
        continue;

      editorial.setAiring(selectedAiring);
      returnValues.add(editorial);

      // This is the most programs we can look up at one time. The actual recommendations shouldn't
      // get anywhere near this value.
      if (returnValues.size() >= 5000)
        break;
    }

    if (Sage.DBG) System.out.println("SDEPG Got " + (returnValues.size() + alreadyExists) +
      " usable recommendations, " + alreadyExists + " are already editorials");
    return returnValues;
  }

  public static List<SDEditorial> getRecommendations(boolean includeIR) throws IOException, SDException
  {
    Set<String> lookupShows = new HashSet<>();
    Airing airings[] = Seeker.getInstance().getInterleavedScheduledAirings();
    if (Sage.DBG) System.out.println("SDEPG Using " + airings.length + " airings for recommendations");
    if (includeIR)
    {
      Airing irAirings[] = Seeker.getInstance().getIRScheduledAirings();
      int copyIndex = airings.length;
      // Don't use more than 3000 ever. It will cause these suggestions to drive the SD
      // recommendations very hard towards shows that might be too derivative from what the user
      // actually watches.
      int copyLength = Math.min(Math.min(IR_LIMIT, irAirings.length), 3000);
      airings = Arrays.copyOf(airings, airings.length + copyLength);
      System.arraycopy(irAirings, 0, airings, copyIndex, copyLength);
      if (Sage.DBG) System.out.println("SDEPG Using " + copyLength + " intelligent airings for recommendations");
    }

    // Gather unique shows by title.
    for (Airing airing : airings)
    {
      Show show = airing.getShow();
      if (show == null)
        continue;

      // Unlikely unless we have a lot of intelligent recordings being figured in and also the limit
      // for one query to Schedules Direct.
      if (lookupShows.size() >= 5000)
        break;

      lookupShows.add(show.getExternalID());
    }

    if (USE_CURRENT_RECORDINGS)
    {
      int addedRecordings = 0;
      MediaFile files[] = Wizard.getInstance().getFiles(DBObject.MEDIA_MASK_TV, false);
      for (MediaFile file : files)
      {
        // We could reach this with the size of some people's collections, but it's ok to not get
        // them all and we'll focus on what is actually marked watched next.
        if (lookupShows.size() >= 5000)
          break;

        if (file.isCompleteRecording())
        {
          Airing airing = file.getContentAiring();
          if (airing == null)
            continue;
          Show show = airing.getShow();
          if (show == null)
            continue;
          lookupShows.add(show.getExternalID());
          addedRecordings++;
        }
      }
      if (Sage.DBG) System.out.println("SDEPG Using " + addedRecordings + " recordings for recommendations");
    }

    List<SDEditorial> returnValues = new ArrayList<>();
    Show shows[] = Wizard.getInstance().getAllShows();
    int currentShow = 0;
    while (true)
    {
      int showCount = 0;
      for (; currentShow < shows.length; currentShow++)
      {
        // 4999 because we could be adding as many as two entries per iteration.
        if (lookupShows.size() >= 4999)
        {
          currentShow--;
          break;
        }

        Show show = shows[currentShow];
        if (show == null)
          continue;

        if (!show.isWatched() || (EXCLUDE_DONT_LIKE && show.isDontLike()))
          continue;

        String externalId = show.getExternalID();
        lookupShows.add(externalId);
        if (externalId.startsWith("EP"))
        {
          lookupShows.add("SH" + externalId.substring(2, externalId.length() - 2));
        }
        showCount++;
      }

      if (lookupShows.size() == 0)
        break;

      if (Sage.DBG) System.out.println("SDEPG Using " + lookupShows.size() + " watched shows for recommendations");

      SDProgram programs[] = SDRipper.ensureSession().getPrograms(lookupShows);
      lookupShows.clear();
      int recommendationCount = 0;
      for (SDProgram program : programs)
      {
        // We are not going to retry content that's not available.
        if (program.getCode() != 0)
          continue;

        SDRecommendation recommendations[] = program.getRecommendations();
        for (SDRecommendation recommendation : recommendations)
        {
          if (returnValues.size() >= 5000)
            break;

          // This should not be happening.
          if (recommendation.getProgramId() ==  null)
            continue;

          SDEditorial editorial = new SDEditorial(recommendation);
          int index = returnValues.indexOf(editorial);
          if (index == -1)
          {
            returnValues.add(editorial);
            recommendationCount++;
          }
          else
          {
            editorial = returnValues.get(index);
          }
          editorial.incrementWeight();
        }
        if (returnValues.size() >= 5000)
          break;
      }
      if (Sage.DBG) System.out.println("SDEPG Got " + recommendationCount + " recommendations");
      if (returnValues.size() >= 5000)
        break;
    }

    // This is limited to not allow more than 5000 recommendations to be returned.
    List<String> orderedPrograms = new ArrayList<>(returnValues.size());
    for (SDEditorial editorial : returnValues)
    {
      orderedPrograms.add(editorial.getRecommendation().getProgramId());
    }

    SDProgram programs[] = SDRipper.ensureSession().getPrograms(orderedPrograms);
    for (int i = 0; i < programs.length; i++)
    {
      SDProgram program = programs[i];
      if (program.getCode() != 0)
       continue;
      String programId = program.getProgramID();
      if (programId == null)
        continue;
      SDEditorial editorial = returnValues.get(i);
      // There's no reason why these would not line up, but just in case.
      if (editorial == null || !programId.equals(editorial.getRecommendation().getProgramId()))
      {
        editorial = null;
        for (SDEditorial returnValue : returnValues)
        {
          if (programId.equals(returnValue.getRecommendation().getProgramId()))
          {
            editorial = returnValue;
            break;
          }
        }
        if (editorial == null)
          continue;
      }

      editorial.setProgram(program);
    }

    return returnValues;
  }
}
