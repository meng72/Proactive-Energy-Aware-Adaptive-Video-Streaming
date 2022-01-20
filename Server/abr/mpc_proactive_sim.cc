#include <fstream>
#include <memory>
#include <chrono>

#include "mpc_proactive_sim.hh"
#include "ws_client.hh"

using namespace std;

MPCProactiveSim::MPCProactiveSim(const WebSocketClient & client,
         const string & abr_name, const YAML::Node & abr_config)
  : ABRAlgo(client, abr_name)
{
  if (abr_config["max_lookahead_horizon"]) {
    max_lookahead_horizon_ = min(
      max_lookahead_horizon_,
      abr_config["max_lookahead_horizon"].as<size_t>());
  }

  if (abr_config["max_power_lookahead_horizon"]) {
    max_power_lookahead_horizon_ = min(
      max_power_lookahead_horizon_,
      abr_config["max_power_lookahead_horizon"].as<size_t>());
  }

  if (abr_config["dis_buf_length"]) {
    dis_buf_length_ = min(dis_buf_length_,
                          abr_config["dis_buf_length"].as<size_t>());
  }

  if (abr_config["rebuffer_length_coeff"]) {
    rebuffer_length_coeff_ = abr_config["rebuffer_length_coeff"].as<double>();
  }

  if (abr_config["ssim_diff_coeff"]) {
    ssim_diff_coeff_ = abr_config["ssim_diff_coeff"].as<double>();
  }

  if (abr_config["max_power_budget"]) {
    max_power_budget_ = min(
      max_power_budget_,
      abr_config["max_power_budget"].as<double>());
  }

  if (abr_config["video_streaming_mode"]) {
    video_streaming_mode_ = abr_config["video_streaming_mode"].as<int>();
  }

  if (abr_name_ == "robust_mpc_proactive_sim") {
    is_robust_ = true;
  }

  if (abr_config["log_dir"]) {
    log_dir_ = abr_config["log_dir"].as<string>();
  }

  unit_buf_length_ = WebSocketClient::MAX_BUFFER_S / dis_buf_length_;

  for (size_t i = 0; i <= dis_buf_length_; i++) {
    real_buffer_[i] = i * unit_buf_length_;
  }

  orig_power_budget_ = max_power_budget_;
  lookahead_formats.resize(max_lookahead_horizon_ + 1);

  append_to_abr_log("client_abr",
    "video_streaming_mode," + to_string(video_streaming_mode_)
    + ",max_power_lookahead_horizon," + to_string(max_power_lookahead_horizon_) + ","
    + to_string(max_lookahead_horizon_)
    + ",max_power_budget," + to_string(max_power_budget_) + "," + to_string(orig_power_budget_)+ "," + to_string(energy_deposit_));
}

void MPCProactiveSim::video_chunk_acked(Chunk && c)
{
  double err = 0;

  if (is_robust_ and last_tp_pred_ > 0) {
    err = fabs(1 - last_tp_pred_ *  c.trans_time / c.size / 1000);
  }

  past_chunks_.push_back({c.ssim, c.size, c.trans_time, err});
  if (past_chunks_.size() > max_num_past_chunks_) {
    past_chunks_.pop_front();
  }

  update_history(c);

  if (selected_formats.size() >= 6) energy_deposit_ = (orig_power_budget_ - power_history_) * time_history_;
  else {
      power_history_ = 0;
      time_history_ = 0;
      energy_deposit_ = 0;
  }
}

VideoFormat MPCProactiveSim::select_video_format()
{
  if (selected_formats.size() == 0) duration_prev_ack_ = 0;
  else duration_prev_ack_ = 1.0 * (client_.select_video_ts().value() - client_.curr_video_ack_ts().value()) / 1000.0;
  
  reinit();

  auto start = std::chrono::system_clock::now();
  size_t ret_format = update_value(0, curr_buffer_, 0, MAX_DIS_ENERGY_LENGTH / 2);
  size_t orig_format = ret_format;
  if (ret_format > prev_selected_fmt_) ret_format = prev_selected_fmt_ + 1;
  auto end = std::chrono::system_clock::now();
  std::chrono::duration<double> diff = end-start;
  if (enable_output_debug_) cout << "-- " + algo_name_ + "duration of select_video_format: " << diff.count() << "s; vwidth: " << client_.channel()->vformats()[ret_format].width  << endl;
  selected_formats.push_back(ret_format);
  append_to_abr_log("client_abr", "select_format," + to_string(curr_round_) + "," + to_string(orig_format) + "," + to_string(ret_format) + "," + to_string(v_[0][curr_buffer_][0][0]));
  prev_selected_fmt_ = ret_format;
  return client_.channel()->vformats()[ret_format];
}

void MPCProactiveSim::reinit()
{
  curr_round_++;

  const auto & channel = client_.channel();
  vformats_ = channel->vformats();
  const unsigned int vduration = channel->vduration();
  const uint64_t next_ts = client_.next_vts().value();

  chunk_length_ = (double) vduration / channel->timescale();
  num_formats_ = vformats_.size();

  /* initialization failed if there is no ready chunk ahead */
  if (channel->vready_frontier().value() < next_ts || num_formats_ == 0) {
    throw runtime_error("no ready chunk ahead");
  }

  lookahead_horizon_ = min(
    max_lookahead_horizon_,
    (channel->vready_frontier().value() - next_ts) / vduration + 1);

  power_lookahead_horizon_ = min(
    max_power_lookahead_horizon_,
    (channel->vready_frontier().value() - next_ts) / vduration + 1);

  curr_buffer_ = min(dis_buf_length_,
                     discretize_buffer(client_.video_playback_buf()));
  video_timeline_ = client_.video_playtrack_timeline();

  /* init curr_ssims */
  if (past_chunks_.size() > 0) {
    is_init_ = false;
    curr_ssims_[0][0] = ssim_db(past_chunks_.back().ssim);
  } else {
    is_init_ = true;
  }

  for (size_t i = 1; i <= lookahead_horizon_; i++) {
    for (size_t j = 0; j < num_formats_; j++) {
      try {
        curr_ssims_[i][j] = ssim_db(
            channel->vssim(vformats_[j], next_ts + vduration * (i - 1)));
      } catch (const exception & e) {
        cerr << "Error occurs when getting the ssim of "
             << next_ts + vduration * (i - 1) << " " << vformats_[j] << endl;
        curr_ssims_[i][j] = MIN_SSIM;
      }
    }
  }

  /* init curr_sending_time */
  size_t num_past_chunks = past_chunks_.size();
  auto it = past_chunks_.begin();
  double max_err = 0;

  for (size_t i = 1; it != past_chunks_.end(); it++, i++) {
    unit_sending_time_[i] = (double) it->trans_time / it->size / 1000;
    max_err = max(max_err, it->pred_err);
  }

  if (not is_robust_) {
    max_err = 0;
  }

  append_to_abr_log("client_abr", "reinit_sending_time," + algo_name_ + ",lookahead_horizon_," + to_string(lookahead_horizon_) + "," + to_string(power_lookahead_horizon_));

  for (size_t i = 1; i <= lookahead_horizon_; i++) {
    double tmp = 0;
    for (size_t j = 0; j < num_past_chunks; j++) {
      tmp += unit_sending_time_[i + j];
    }

    if (num_past_chunks != 0) {
      double unit_st = tmp / num_past_chunks;

      if (i == 1) {
        last_tp_pred_ = 1 / unit_st;
      }

      unit_sending_time_[i + num_past_chunks] = unit_st * (1 + max_err);
    } else {
      /* set the sending time to be a default hight value */
      unit_sending_time_[i + num_past_chunks] = HIGH_SENDING_TIME;
    }

    const auto & data_map = channel->vdata(next_ts + vduration * (i - 1));
    double curr_sending_time;

    for (size_t j = 0; j < num_formats_; j++) {
      int curr_size = get<1>(data_map.at(vformats_[j]));
      try {
        curr_sending_time = curr_size * unit_sending_time_[i + num_past_chunks];
      } catch (const exception & e) {
        cerr << "Error occurs when getting the video size of "
             << next_ts + vduration * (i - 1) << " " << vformats_[j] << endl;
        curr_sending_time = HIGH_SENDING_TIME;
      }
      curr_sending_time_[i][j] = curr_sending_time;

      net_power_[i][j] = compute_net_power(curr_size, curr_sending_time, 1.0);
      append_to_abr_log("client_abr", to_string(i) + "," + to_string(j) + "," + to_string(curr_sending_time) + "," + to_string(curr_sending_time) + "," + to_string(curr_size) + "," + to_string(net_power_[i][j]));
    }
  }
}

size_t MPCProactiveSim::update_value(
  size_t i, size_t curr_buffer, size_t curr_format, size_t curr_energy)
{
  flag_[i][curr_buffer][curr_format][curr_energy] = curr_round_;

  if (enable_output_debug_) cout << algo_name_ << "::update_value: i, cbuf, cfmt, ctime, cpwr, cp_value, pbudget, vtimeline --" << i << "," << curr_buffer << "," << curr_format << "," << 0 << "," << curr_energy << "," << (curr_energy + 1) * unit_energy_ << "," << energy_deposit_ << "," << video_timeline_ << endl;
  lookahead_formats[i] = curr_format;

  if (i == lookahead_horizon_) {
    v_[i][curr_buffer][curr_format][curr_energy] = curr_ssims_[i][curr_format];
    return 0;
  }

  size_t best_next_format = num_formats_;
  double max_qvalue = 0;
  for (size_t next_format = 0; next_format < num_formats_; next_format++) {
    lookahead_formats[i + 1] = next_format; // save lookahead_formats

    double qvalue = get_qvalue(
      i, curr_buffer, curr_format, next_format, curr_energy);
    if (best_next_format == num_formats_ or qvalue > max_qvalue) {
      max_qvalue = qvalue;
      best_next_format = next_format;
    }
  }
  v_[i][curr_buffer][curr_format][curr_energy] = max_qvalue;

  if (max_qvalue >= 0 || i != 0) {
    return best_next_format;
  } else {
    return min_power_format();
  }
}

double MPCProactiveSim::get_qvalue(size_t i, size_t curr_buffer, size_t curr_format,
  size_t next_format, size_t curr_energy)
{
  double real_rebuffer = curr_sending_time_[i + 1][next_format]
                         - real_buffer_[curr_buffer];
  size_t next_buffer = discretize_buffer(max(0.0, -real_rebuffer + chunk_length_));
  next_buffer = min(next_buffer, dis_buf_length_);

  double est_energy = MIN_PER_ENERGY + curr_energy * unit_energy_ + unit_energy_ * 0.5;
  double trans_time = curr_sending_time_[i + 1][next_format];
  double tail_time = 0;
  double idle_time = 0;
  if (i == 0) idle_time = duration_prev_ack_;
  if (real_rebuffer > buffer_threshold_) idle_time += real_rebuffer - buffer_threshold_;
  if (idle_time > 0.2) {
      tail_time = 0.2;
      idle_time = idle_time - 0.2;
  } else if (idle_time <= 0.2 && idle_time > 0) {
      tail_time = idle_time;
      idle_time = 0;
  }

  double est_time = trans_time + tail_time + idle_time;
  double vid_pwr = compute_video_power(video_timeline_, i * 2.002); 
  est_energy += net_power_[i+1][next_format] * trans_time + net_model_tail_ * tail_time + net_model_idle_ * idle_time;
  est_energy += vid_pwr * (trans_time + tail_time + idle_time - real_rebuffer) + vid_pwr * real_rebuffer;
  est_energy -= orig_power_budget_ * est_time;

  if (i == power_lookahead_horizon_ - 1 && est_energy > energy_deposit_) {
      return -99999;
  }

  size_t next_energy = discretize_energy(est_energy);

  if (is_init_ and i == 0) {
    return curr_ssims_[i][curr_format]
           - rebuffer_length_coeff_ * max(0.0, real_rebuffer)
           + get_value(i + 1, next_buffer, next_format, next_energy);
  }
  return curr_ssims_[i][curr_format]
         - ssim_diff_coeff_ * fabs(curr_ssims_[i][curr_format]
                                   - curr_ssims_[i + 1][next_format])
         - rebuffer_length_coeff_ * max(0.0, real_rebuffer)
         + get_value(i + 1, next_buffer, next_format, next_energy);
}

double MPCProactiveSim::get_value(size_t i, size_t curr_buffer, size_t curr_format,
  size_t curr_energy)
{
  if (flag_[i][curr_buffer][curr_format][curr_energy] != curr_round_) {
    update_value(i, curr_buffer, curr_format, curr_energy);
  }
  return v_[i][curr_buffer][curr_format][curr_energy];
}

size_t MPCProactiveSim::discretize_buffer(double buf)
{
  return (buf + unit_buf_length_ * 0.5) / unit_buf_length_;
}

size_t MPCProactiveSim::discretize_energy(double energy)
{
  if (energy >= MAX_PER_ENERGY) return MAX_DIS_ENERGY_LENGTH - 1;
  if (energy < MIN_PER_ENERGY) return 0;
  return (energy - MIN_PER_ENERGY) / unit_energy_;
}

void MPCProactiveSim::update_history(Chunk c)
{
  double video_timeline = client_.video_playtrack_timeline();
  double cum_rebuffer = client_.cum_rebuffer();

  uint64_t prev_video_ack_ts = client_.prev_video_ack_ts().value();
  uint64_t curr_video_ack_ts = client_.curr_video_ack_ts().value();
  double duration = 1.0 * (curr_video_ack_ts - prev_video_ack_ts) / 1000.0;
  double trans_time = 1.0 * c.trans_time / 1000.0;
  double vid_idle_time = cum_rebuffer - prev_cum_rebuffer_;

  size_t last_transmit_format = selected_formats.back();
  const auto & data_map = client_.channel()
    ->vdata(client_.next_vts().value() - client_.channel()->vduration());

  int video_size = get<1>(data_map.at(
    client_.channel()->vformats()[last_transmit_format]));
  double net_pwr = compute_net_power(video_size, trans_time, 1.0);
  double vid_pwr = compute_video_power(video_timeline, 0.0); // calculate video power based on video_timeline
  double tail_time = 0;
  double idle_time = 0;
  if (duration - trans_time > 0) idle_time = duration - trans_time;
  if (idle_time > 0.2) {
      tail_time = 0.2;
      idle_time = idle_time - 0.2;
  } else if (idle_time <= 0.2 && idle_time > 0) {
      tail_time = idle_time;
      idle_time = 0;
  }
  double chunk_energy = net_pwr * trans_time + net_model_tail_ * tail_time + net_model_idle_ * idle_time;
  chunk_energy += vid_pwr * (duration - vid_idle_time) + vid_pwr * vid_idle_time; //360-degree
  double real_energy = power_history_ * time_history_ + chunk_energy;
  time_history_ += duration;
  power_history_ = real_energy / time_history_;

  prev_cum_rebuffer_ = cum_rebuffer;

  append_to_abr_log("client_abr", algo_name_ + ", video_timeline_" + to_string(curr_round_) + ", duration, trans_time, video_size, fmt, power_history_, energy_deposit_," + to_string(video_timeline) + "," + to_string(duration) + "," + to_string(trans_time) + "," + to_string(video_size) + "," + to_string(last_transmit_format) + "," + to_string(power_history_) + "," + to_string(energy_deposit_) + "," + to_string(chunk_energy / duration) + "," + to_string(net_pwr) + "," + to_string(vid_pwr) + "," + to_string(vid_idle_time));
  if (enable_output_debug_) cout << "video_chunk_acked: " << prev_video_ack_ts << "," << curr_video_ack_ts << endl;
}

double MPCProactiveSim::compute_net_power(int vsize, double trans_time, double active_ratio)
{
  if (active_ratio > 1) active_ratio = 1.0;
  double xput = 8.0 * vsize / (trans_time * 1024.0);
  return (net_model_coeff_ * xput + net_model_intercept_) * active_ratio
  	+ net_model_tail_ * (1.0 - active_ratio);
}

double MPCProactiveSim::compute_video_power(double base_time, double offset_time) 
{
  double v_seg_length = 2.002; 
  size_t f_id = (base_time + offset_time) / v_seg_length;
  int lf_id = f_id - selected_formats.size();

  // no known video format should be playing at base_time + offset_time
  if (lf_id >= (int) lookahead_formats.size() - 1) return get_video_power_based_on_vwdith(vformats_[0].width); 
  else if (lf_id >= 0) return get_video_power_based_on_vwdith(vformats_[lookahead_formats.at(lf_id + 1)].width);
  return get_video_power_based_on_vwdith(vformats_[selected_formats.at(f_id)].width);
}

// Power model for video decoding and displaying
double MPCProactiveSim::get_video_power_based_on_vwdith(int vwidth)
{
  double base_pwr = 0;
  if (video_streaming_mode_ == 1) { // 360-degree video streaming w/ only figure control
    switch (vwidth) {
      case 3840:
        base_pwr = 227.42;
        break;
      case 2560:
        base_pwr = 206.76;
        break;
      case 1920:
        base_pwr = 199.00;
        break;
      case 1280:
        base_pwr = 194.10;
        break;
      case 854:
        base_pwr = 191.34;
        break;
      case 640:
        base_pwr = 189.71;
        break;
      case 426:
        base_pwr = 185.21;
        break;
      case 256:
        base_pwr = 185.70;
        break;
      default:
        base_pwr = 115.39;
        break;
    }
  } else if (video_streaming_mode_ == 2) { // 360-degree video streaming w/ gyrosensors
    switch (vwidth) {
      case 3840:
        base_pwr = 259.00;
        break;
      case 2560:
        base_pwr = 235.00;
        break;
      case 1920:
        base_pwr = 234.00;
        break;
      case 1280:
        base_pwr = 226.00;
        break;
      case 854:
        base_pwr = 218.00;
        break;
      case 640:
        base_pwr = 213.00;
        break;
      case 426:
        base_pwr = 213.00;
        break;
      case 256:
        base_pwr = 213.00;
        break;
      default:
        base_pwr = 115.39;
        break;
    }
  }
  return base_pwr; 
}

size_t MPCProactiveSim::min_power_format()
{
  size_t best_next_format = 0; 
  return best_next_format;
}

void MPCProactiveSim::append_to_abr_log(const string & log_stem, const string & log_line)
{
  string log_name = log_stem + ".1.log";
  string log_path = log_dir_ + "/" + log_name;

  ofstream myfile;
  myfile.open (log_path, ios_base::app);
  myfile << log_line + "\n";
  myfile.close();
}
